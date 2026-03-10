"""
큐리오 YouTube Shorts 완전 자동화 파이프라인
매일 08:00 Windows 작업 스케줄러로 자동 실행

파이프라인:
  0. YouTube OAuth2 인증
  1. 채널 기존 영상 제목 수집 (중복 방지)
  2. Gemini 주제 10개 생성 → 중복 제거 → 1개 선택
  3. 대본 생성 (Gemini)
  4. 이미지 생성 (Imagen, 실패 시 그라디언트 대체)
  5. TTS (ElevenLabs 우선 → edge-tts → OpenAI fallback)
  6. 영상 합성 (moviepy + PIL)
  7. YouTube 업로드 (공개)
  8. Telegram 성공 알림

오류 시: Telegram 알림 후 즉시 종료 (logs/ 에 상세 기록)
"""

import datetime
import json
import logging
import os
import sys
import traceback
from difflib import SequenceMatcher
from pathlib import Path


# ── 로깅 설정 ────────────────────────────────────────────────────────────────

def setup_logging():
    log_dir = Path("logs")
    log_dir.mkdir(exist_ok=True)
    log_file = log_dir / f"auto_{datetime.date.today()}.log"

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        handlers=[
            logging.FileHandler(log_file, encoding="utf-8"),
            logging.StreamHandler(sys.stdout),
        ],
    )
    return str(log_file)


# ── 설정 로드 ─────────────────────────────────────────────────────────────────

def load_config(path: str = "config.json") -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_run_log(log_file: str, entry: dict):
    records = []
    if os.path.exists(log_file):
        with open(log_file, "r", encoding="utf-8") as f:
            records = json.load(f)
    records.append(entry)
    with open(log_file, "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=2)


# ── YouTube 채널 제목 수집 ─────────────────────────────────────────────────────

def get_channel_video_titles(youtube_service) -> list[str]:
    """내 채널(@tguyjin73) 기존 영상 제목 전부 수집."""
    titles = []
    try:
        next_page = None
        while True:
            params = {
                "part": "snippet",
                "forMine": True,
                "type": "video",
                "maxResults": 50,
                "order": "date",
            }
            if next_page:
                params["pageToken"] = next_page

            resp = youtube_service.search().list(**params).execute()
            for item in resp.get("items", []):
                title = item["snippet"].get("title", "")
                if title:
                    titles.append(title)

            next_page = resp.get("nextPageToken")
            if not next_page:
                break

        logging.info(f"  채널 기존 영상 {len(titles)}개 제목 수집")
    except Exception as e:
        logging.warning(f"  채널 검색 실패 (로컬 로그만 사용): {e}")

    return titles


# ── 중복 주제 판별 ────────────────────────────────────────────────────────────

def is_similar(a: str, b: str, threshold: float = 0.72) -> bool:
    """두 제목의 유사도가 threshold 이상이면 중복으로 판단."""
    return SequenceMatcher(None, a.lower(), b.lower()).ratio() >= threshold


def is_duplicate_topic(new_title: str, existing: list[str]) -> bool:
    for title in existing:
        if is_similar(new_title, title):
            return True
    return False


# ── 주요 파이프라인 단계 ─────────────────────────────────────────────────────

def step_select_topic(cfg: dict, channel_titles: list[str], used_titles: list[str]) -> dict:
    """중복 없는 주제 1개 선택 (최대 3번 시도)."""
    from modules.script_generator import generate_topics_dynamic

    all_existing = channel_titles + used_titles
    selected = None

    for attempt in range(3):
        candidates = generate_topics_dynamic(
            api_key=cfg["gemini"]["api_key"],
            model=cfg["gemini"]["model"],
            used_titles=used_titles,
            count=10,
        )
        for topic in candidates:
            if not is_duplicate_topic(topic["title"], all_existing):
                selected = topic
                break
        if selected:
            break
        logging.info(f"  시도 {attempt + 1}/3: 중복 없는 주제 없음, 재시도")

    if not selected and candidates:
        logging.warning("  중복 완전 회피 실패 → 첫 번째 후보 사용")
        selected = candidates[0]

    if not selected:
        raise RuntimeError("주제 후보를 생성할 수 없습니다.")

    logging.info(f"  선택된 주제: [{selected.get('category','')}] {selected['title']}")
    return selected


def step_generate_script(cfg: dict, topic: dict) -> dict:
    from modules.script_generator import generate_script
    script = generate_script(
        topic=topic,
        api_key=cfg["gemini"]["api_key"],
        model=cfg["gemini"]["model"],
    )
    segs = len(script.get("segments", []))
    chars = len(script.get("full_script", ""))
    logging.info(f"  대본: {segs}개 세그먼트, {chars}자")
    return script


def step_generate_images(cfg: dict, script: dict) -> list:
    from modules.image_generator import generate_segment_images
    scenes = generate_segment_images(
        script_data=script,
        api_key=cfg["gemini"]["api_key"],
        imagen_model=cfg["gemini"].get("imagen_model", "imagen-4.0-fast-generate-001"),
        style_prefix=cfg.get("image", {}).get("style_prefix", "3D Pixar animation style"),
        output_dir=cfg.get("image", {}).get("output_dir", "output/images"),
        openai_api_key=cfg.get("openai", {}).get("api_key", ""),
        dalle3_quality=cfg.get("image", {}).get("dalle3_quality", "standard"),
    )
    ok = sum(1 for s in scenes if not s.get("error"))
    logging.info(f"  이미지: {ok}/{len(scenes)}개 성공")
    return scenes


def step_generate_tts(cfg: dict, script: dict, date_str: str) -> dict:
    from modules.tts_generator import generate_tts
    audio_path = os.path.join(cfg["output"]["audio_dir"], f"tts_{date_str}.mp3")
    full_script = script.get("full_script", " ".join(
        s.get("text") or s.get("content") or s.get("narration") or ""
        for s in script.get("segments", [])
    ))
    el_cfg = cfg.get("elevenlabs", {})
    result = generate_tts(
        script_text=full_script,
        output_path=audio_path,
        api_key=cfg["openai"]["api_key"],
        model=cfg["openai"]["tts_model"],
        voice=cfg["openai"]["tts_voice"],
        speed=cfg["openai"]["tts_speed"],
        elevenlabs_api_key=el_cfg.get("api_key", ""),
        elevenlabs_voice_id=el_cfg.get("voice_id", ""),
        elevenlabs_model=el_cfg.get("model", "eleven_multilingual_v2"),
    )
    logging.info(f"  TTS: {result['duration_seconds']:.1f}초")
    return result


def step_build_video(cfg: dict, scenes: list, tts_result: dict, date_str: str) -> str:
    from modules.video_builder import build_video_from_scenes
    video_path = os.path.join(cfg["output"]["video_dir"], f"shorts_{date_str}.mp4")
    build_video_from_scenes(
        scenes=scenes,
        audio_path=tts_result["audio_path"],
        audio_duration=tts_result["duration_seconds"],
        output_path=video_path,
        font_path=cfg["video"]["font_path"],
        luma_video_path=None,
    )
    size_mb = os.path.getsize(video_path) / 1024 / 1024
    logging.info(f"  영상: {video_path} ({size_mb:.1f} MB)")
    return video_path


def step_upload_youtube(cfg: dict, youtube_service, script: dict, topic: dict, video_path: str) -> str:
    from modules.youtube_uploader import upload_video

    yt_title = cfg["youtube"]["title_template"].format(topic_title=topic["title"])
    yt_desc = cfg["youtube"]["description_template"].format(
        description=script.get("description", "")
    )
    yt_tags = list(dict.fromkeys(
        cfg["youtube"]["default_tags"]
        + script.get("tags", [])
        + topic.get("keywords", [])
    ))[:15]

    response = upload_video(
        youtube=youtube_service,
        file_path=video_path,
        title=yt_title,
        description=yt_desc,
        tags=yt_tags,
        category_id=cfg["youtube"]["category_id"],
        privacy_status="public",           # 공개 업로드
    )
    youtube_id = response.get("id", "unknown")
    logging.info(f"  업로드 완료: https://youtube.com/shorts/{youtube_id}")
    return youtube_id


# ── 메인 파이프라인 ───────────────────────────────────────────────────────────

def run_pipeline(cfg: dict):
    date_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    log_file = cfg["logging"]["log_file"]

    # ── 0. YouTube OAuth2 인증 ─────────────────────────────────────────────
    logging.info("[0/7] YouTube OAuth2 인증")
    from modules.youtube_uploader import get_authenticated_service
    youtube_service = get_authenticated_service(cfg["youtube"]["client_secrets_file"])
    logging.info("  인증 완료")

    # ── 1. 채널 기존 영상 제목 수집 ────────────────────────────────────────
    logging.info("[1/7] 채널 기존 영상 제목 수집")
    channel_titles = get_channel_video_titles(youtube_service)

    # 로컬 로그에서 사용 이력 로드
    used_titles = []
    if os.path.exists(log_file):
        with open(log_file, "r", encoding="utf-8") as f:
            used_titles = [e.get("topic_title", "") for e in json.load(f) if e.get("topic_title")]

    # ── 2. 주제 선택 ───────────────────────────────────────────────────────
    logging.info("[2/7] 주제 선택 (중복 제외)")
    topic = step_select_topic(cfg, channel_titles, used_titles)

    # ── 3. 대본 생성 ───────────────────────────────────────────────────────
    logging.info("[3/7] 대본 생성")
    script = step_generate_script(cfg, topic)

    # ── 4. 이미지 생성 ─────────────────────────────────────────────────────
    logging.info("[4/7] 이미지 생성")
    scenes = step_generate_images(cfg, script)

    # ── 5. TTS 음성 생성 ───────────────────────────────────────────────────
    logging.info("[5/7] TTS 음성 생성")
    tts_result = step_generate_tts(cfg, script, date_str)

    # ── 6. 영상 합성 ───────────────────────────────────────────────────────
    logging.info("[6/7] 영상 합성")
    video_path = step_build_video(cfg, scenes, tts_result, date_str)

    # ── 7. YouTube 업로드 (공개) ──────────────────────────────────────────
    logging.info("[7/7] YouTube 업로드 (공개)")
    youtube_id = step_upload_youtube(cfg, youtube_service, script, topic, video_path)

    # ── 로그 저장 ──────────────────────────────────────────────────────────
    save_run_log(log_file, {
        "timestamp": datetime.datetime.now().isoformat(),
        "topic_title": topic["title"],
        "category": topic.get("category", ""),
        "output_path": video_path,
        "youtube_video_id": youtube_id,
        "privacy": "public",
        "duration_seconds": round(tts_result["duration_seconds"], 1),
    })

    # ── Telegram 성공 알림 ─────────────────────────────────────────────────
    bot_token = cfg["telegram"]["bot_token"]
    chat_id = cfg["telegram"]["chat_id"]
    if bot_token and chat_id:
        from modules.notifier import notify_success
        notify_success(bot_token, chat_id, topic["title"], youtube_id,
                       tts_result["duration_seconds"])
        logging.info("  Telegram 성공 알림 전송")

    return youtube_id


# ── 엔트리포인트 ─────────────────────────────────────────────────────────────

def main():
    # 작업 디렉토리를 스크립트 위치로 고정 (Task Scheduler에서 실행 시 필요)
    os.chdir(Path(__file__).parent)

    log_file_path = setup_logging()

    logging.info("=" * 60)
    logging.info("큐리오 YouTube Shorts 자동화 파이프라인 시작")
    logging.info(f"실행 시각: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    logging.info("=" * 60)

    try:
        cfg = load_config()
    except Exception as e:
        logging.error(f"config.json 로드 실패: {e}")
        sys.exit(1)

    # 출력 디렉토리 생성
    for d in [cfg["output"]["audio_dir"], cfg["output"]["video_dir"],
              cfg.get("image", {}).get("output_dir", "output/images"), "logs"]:
        Path(d).mkdir(parents=True, exist_ok=True)

    videos_per_day = cfg.get("schedule", {}).get("videos_per_day", 1)
    logging.info(f"오늘 목표: {videos_per_day}개 영상 생성")

    success_count = 0
    for i in range(videos_per_day):
        logging.info(f"\n{'='*60}")
        logging.info(f"📹 영상 {i+1}/{videos_per_day} 시작")
        logging.info(f"{'='*60}")
        try:
            youtube_id = run_pipeline(cfg)
            success_count += 1
            logging.info(f"✅ 영상 {i+1}/{videos_per_day} 완료! https://youtube.com/shorts/{youtube_id}")

            # 영상 사이 간격 (마지막은 대기 불필요)
            if i < videos_per_day - 1:
                import time
                wait_sec = 30
                logging.info(f"  다음 영상까지 {wait_sec}초 대기...")
                time.sleep(wait_sec)

        except Exception as e:
            error_detail = traceback.format_exc()
            logging.error(f"영상 {i+1} 파이프라인 실패:\n{error_detail}")

            # Telegram 오류 알림
            try:
                bot_token = cfg["telegram"]["bot_token"]
                chat_id = cfg["telegram"]["chat_id"]
                if bot_token and chat_id:
                    from modules.notifier import notify_error
                    notify_error(bot_token, chat_id, str(e), error_detail)
                    logging.info("  Telegram 오류 알림 전송")
            except Exception:
                pass

            # 1개 실패해도 나머지 계속 시도
            if i < videos_per_day - 1:
                logging.info("  다음 영상 계속 시도...")
                continue
            else:
                if success_count == 0:
                    sys.exit(1)

    logging.info(f"\n{'='*60}")
    logging.info(f"🏁 오늘 작업 완료: {success_count}/{videos_per_day}개 성공")
    logging.info(f"{'='*60}")


if __name__ == "__main__":
    main()
