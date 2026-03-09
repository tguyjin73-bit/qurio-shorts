"""
YouTube Shorts 자동 생성 시스템 - 자동화 실행 스크립트
(검증 완료 후 매일 자동 업로드용)

사용법:
    python main.py                  # 단일 실행 (테스트)
    python main.py --dry-run        # 업로드/알림 없이 영상만 생성
    python main.py --daily          # 매일 config의 schedule.time에 자동 실행
    python main.py --daily --time 09:00
"""

import argparse
import datetime
import json
import logging
import os
import time as time_module
from pathlib import Path

from modules.topic_selector import TopicSelector
from modules.script_generator import generate_script, pick_topic_options
from modules.tts_generator import generate_tts
from modules.video_builder import build_video
from modules.youtube_uploader import get_authenticated_service, upload_video
from modules.notifier import notify_upload_complete


def load_config(path: str = "config.json") -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def setup_directories(cfg: dict):
    for d in [cfg["output"]["audio_dir"], cfg["output"]["video_dir"],
              cfg["pexels"]["video_dir"], "logs"]:
        Path(d).mkdir(parents=True, exist_ok=True)


def save_log(log_file: str, entry: dict):
    log = []
    if os.path.exists(log_file):
        with open(log_file, "r", encoding="utf-8") as f:
            log = json.load(f)
    log.append(entry)
    with open(log_file, "w", encoding="utf-8") as f:
        json.dump(log, f, ensure_ascii=False, indent=2)


def run_once(cfg: dict, dry_run: bool = False):
    """단일 Shorts 영상 생성 + 업로드 파이프라인 (자동화용)."""
    date_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

    # ── 1. 주제 선택 ──────────────────────────────────────────────
    logging.info("[1/6] 주제 선택")
    selector = TopicSelector(cfg["topics"]["source_file"], cfg["topics"]["avoid_repeat_count"])
    topic, category = selector.select()
    logging.info(f"  {category['emoji']} {category['name']} | {topic['title']}")

    # ── 2. Claude 스크립트 생성 ────────────────────────────────────
    logging.info("[2/6] Claude 스크립트 생성")
    script_data = generate_script(
        topic=topic,
        category=category,
        api_key=cfg["gemini"]["api_key"],
        model=cfg["gemini"]["model"],
    )
    logging.info(f"  제목: {script_data['title']}")

    # ── 3. TTS 음성 생성 ────────────────────────────────────────────
    logging.info("[3/6] TTS 음성 생성")
    audio_path = os.path.join(cfg["output"]["audio_dir"], f"tts_{topic['id']}_{date_str}.mp3")
    voice = category.get("tts_voice", cfg["openai"]["tts_voice"])
    tts_result = generate_tts(
        script_text=script_data["full_script"],
        output_path=audio_path,
        api_key=cfg["openai"]["api_key"],
        model=cfg["openai"]["tts_model"],
        voice=voice,
        speed=cfg["openai"]["tts_speed"],
    )
    logging.info(f"  길이: {tts_result['duration_seconds']:.1f}초")

    # ── 4. Pexels 배경 영상 자동 선택 ──────────────────────────────
    bg_video_path = None
    if cfg["pexels"]["api_key"]:
        logging.info("[4/6] Pexels 배경 영상 검색")
        try:
            from modules.pexels_client import search_videos, download_video
            keywords = script_data.get("pexels_keywords", ["technology", "product design"])
            results = search_videos(keywords[0], cfg["pexels"]["api_key"], per_page=3)
            if results:
                chosen = results[0]
                bg_video_path = download_video(chosen["download_url"], cfg["pexels"]["video_dir"], chosen["id"])
                logging.info(f"  배경 영상: {bg_video_path}")
        except Exception as e:
            logging.warning(f"  Pexels 실패, 그라디언트 배경 사용: {e}")
    else:
        logging.info("[4/6] Pexels API 키 없음 → 그라디언트 배경 사용")

    # ── 5. 영상 합성 ─────────────────────────────────────────────────
    logging.info("[5/6] 영상 합성")
    video_filename = cfg["output"]["filename_template"].format(
        category=category["id"], date=date_str
    )
    video_path = os.path.join(cfg["output"]["video_dir"], video_filename)
    build_video(
        script_data=script_data,
        audio_path=tts_result["audio_path"],
        audio_duration=tts_result["duration_seconds"],
        output_path=video_path,
        category_data=category,
        font_path=cfg["video"]["font_path"],
        bg_video_path=bg_video_path,
        overlay_opacity=cfg["video"].get("overlay_opacity", 0.55),
    )
    logging.info(f"  영상: {video_path}")

    # ── 6. YouTube 업로드 + 텔레그램 알림 ────────────────────────────
    youtube_id = "dry-run"
    if not dry_run:
        logging.info("[6/6] YouTube 업로드")
        yt_service = get_authenticated_service(cfg["youtube"]["client_secrets_file"])
        yt_title = cfg["youtube"]["title_template"].format(topic_title=topic["title"])
        yt_desc = cfg["youtube"]["description_template"].format(
            gpt_description=script_data.get("description", "")
        )
        yt_tags = list(dict.fromkeys(
            cfg["youtube"]["default_tags"] + script_data.get("tags", []) + topic.get("keywords", [])
        ))[:15]

        response = upload_video(
            youtube=yt_service,
            file_path=video_path,
            title=yt_title,
            description=yt_desc,
            tags=yt_tags,
            category_id=cfg["youtube"]["category_id"],
            privacy_status=cfg["youtube"]["privacy_status"],
        )
        youtube_id = response.get("id", "unknown")
        logging.info(f"  https://youtube.com/shorts/{youtube_id}")

        # 텔레그램 알림
        if cfg["telegram"]["bot_token"] and cfg["telegram"]["chat_id"]:
            notify_upload_complete(
                bot_token=cfg["telegram"]["bot_token"],
                chat_id=cfg["telegram"]["chat_id"],
                topic_title=topic["title"],
                youtube_id=youtube_id,
            )
            logging.info("  텔레그램 알림 전송 완료")
    else:
        logging.info("[6/6] dry-run - 업로드/알림 건너뜀")

    selector.mark_used(topic["id"])
    save_log(cfg["logging"]["log_file"], {
        "timestamp": datetime.datetime.now().isoformat(),
        "topic_id": topic["id"],
        "topic_title": topic["title"],
        "output_path": video_path,
        "youtube_video_id": youtube_id,
    })
    logging.info("[완료]\n")
    return youtube_id


def main():
    parser = argparse.ArgumentParser(description="YouTube Shorts 자동 생성기")
    parser.add_argument("--daily", action="store_true", help="매일 자동 실행 모드")
    parser.add_argument("--time", default=None, help="실행 시각 HH:MM")
    parser.add_argument("--dry-run", action="store_true", help="업로드 없이 영상만 생성")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(message)s", datefmt="%H:%M:%S")

    cfg = load_config()
    setup_directories(cfg)

    if not cfg["gemini"]["api_key"]:
        logging.error("config.json의 gemini.api_key를 입력해주세요.")
        return

    if args.daily:
        import schedule
        run_time = args.time or cfg["schedule"]["time"]
        logging.info(f"매일 {run_time}에 자동 실행. Ctrl+C로 중지.")
        schedule.every().day.at(run_time).do(run_once, cfg=cfg, dry_run=args.dry_run)
        while True:
            schedule.run_pending()
            time_module.sleep(30)
    else:
        run_once(cfg, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
