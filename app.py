"""
YouTube Shorts 자동 생성 - 6단계 위자드 Streamlit 앱

단계:
  1. 주제 선택    (Gemini가 5개 동적 생성 → 사용자 클릭)
  2. 대본 검토    (Gemini 생성 → 자막 미리보기 포함)
  3. 이미지 확인  (Gemini Imagen 세그먼트별 9:16 생성, 3D 픽사 스타일)
  4. 영상 미리보기 (Luma AI 5초 영상 + TTS + 이미지슬라이드)
  5. YouTube 업로드 (비공개로 업로드)

실행: streamlit run app.py
"""

import datetime
import json
import os
import threading
from pathlib import Path

import streamlit as st

st.set_page_config(page_title="Shorts 자동 생성기", page_icon="🎬", layout="wide")

# Streamlit 내부 stException 박스만 CSS로 숨김 (JS 제거 — 콘텐츠 블록 오숨김 버그 방지)
st.markdown("""
<style>
[data-testid="stException"] { display: none !important; }
</style>
""", unsafe_allow_html=True)

CONFIG_PATH = "config.json"
LOG_PATH = "logs/generation_log.json"

# ── 헬퍼 함수 ─────────────────────────────────────────────────────────────────

def load_config() -> dict:
    """config.json 로드. 클라우드 배포 시 st.secrets로 API 키 오버라이드."""
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    else:
        # 클라우드 환경: config.json 없을 때 기본 구조 생성
        cfg = {
            "gemini": {"api_key": "", "model": "gemini-2.5-flash",
                       "imagen_model": "imagen-4.0-fast-generate-001"},
            "luma": {"api_key": "", "imgbb_api_key": "", "prompt_suffix": "Smooth cinematic camera movement"},
            "openai": {"api_key": "", "tts_model": "tts-1", "tts_voice": "echo", "tts_speed": 1.0},
            "telegram": {"bot_token": "", "chat_id": ""},
            "image": {"output_dir": "output/images",
                      "style_prefix": "3D CGI professional animation, adult characters only"},
            "video": {"width": 720, "height": 1280, "fps": 24, "codec": "libx264",
                      "audio_codec": "aac", "bitrate": "2000k",
                      "font_path": "assets/fonts/NanumGothicBold.ttf",
                      "overlay_opacity": 0.45},
            "output": {"audio_dir": "output/audio", "video_dir": "output/video",
                       "luma_dir": "output/luma"},
            "youtube": {"client_secrets_file": "client_secret.json",
                        "category_id": "28", "privacy_status": "private",
                        "title_template": "{topic_title} #Shorts",
                        "description_template": "{description}\n\n제품개발 정보성 유튜브 쇼츠!\n#제품개발 #스타트업 #Shorts",
                        "default_tags": ["Shorts", "제품개발", "스타트업", "정보성"]},
            "logging": {"log_file": "logs/generation_log.json"},
        }

    # Streamlit Cloud secrets 오버라이드 (배포 환경)
    try:
        secrets = st.secrets
        if "gemini_api_key" in secrets:
            cfg["gemini"]["api_key"] = secrets["gemini_api_key"]
        if "openai_api_key" in secrets:
            cfg["openai"]["api_key"] = secrets["openai_api_key"]
        if "luma_api_key" in secrets:
            cfg["luma"]["api_key"] = secrets["luma_api_key"]
        if "imgbb_api_key" in secrets:
            cfg["luma"]["imgbb_api_key"] = secrets["imgbb_api_key"]
        if "telegram_bot_token" in secrets:
            cfg["telegram"]["bot_token"] = secrets["telegram_bot_token"]
        if "telegram_chat_id" in secrets:
            cfg["telegram"]["chat_id"] = secrets["telegram_chat_id"]
        # YouTube OAuth 인증 파일 복원 (base64 → 임시 파일)
        if "youtube_token_b64" in secrets and secrets["youtube_token_b64"]:
            import base64, tempfile
            token_data = base64.b64decode(secrets["youtube_token_b64"])
            token_path = os.path.join(tempfile.gettempdir(), "token.pickle")
            with open(token_path, "wb") as _f:
                _f.write(token_data)
            cfg["youtube"]["token_path"] = token_path
        if "youtube_client_secret_b64" in secrets and secrets["youtube_client_secret_b64"]:
            import base64, tempfile
            cs_data = base64.b64decode(secrets["youtube_client_secret_b64"])
            cs_path = os.path.join(tempfile.gettempdir(), "client_secret.json")
            with open(cs_path, "w", encoding="utf-8") as _f:
                _f.write(cs_data.decode("utf-8"))
            cfg["youtube"]["client_secrets_file"] = cs_path
    except Exception:
        pass  # 로컬 환경에선 secrets 없어도 정상

    return cfg


def save_config(cfg: dict):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def load_log() -> list:
    if not os.path.exists(LOG_PATH):
        return []
    with open(LOG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def save_log(entry: dict):
    log = load_log()
    log.append(entry)
    os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
    with open(LOG_PATH, "w", encoding="utf-8") as f:
        json.dump(log, f, ensure_ascii=False, indent=2)


def setup_dirs():
    for d in ["output/audio", "output/video", "output/images", "output/luma", "logs", "assets/fonts"]:
        Path(d).mkdir(parents=True, exist_ok=True)


def check_ready(cfg: dict) -> list:
    missing = []
    if not cfg["gemini"]["api_key"]:
        missing.append("Gemini API 키")
    if not cfg["openai"]["api_key"]:
        missing.append("OpenAI API 키 (TTS용)")
    return missing


def run_with_progress(fn, args=(), kwargs=None, label="처리 중...", est_seconds=20):
    """API 호출을 백그라운드 스레드에서 실행하면서 스피너 표시."""
    if kwargs is None:
        kwargs = {}
    result_holder = [None]
    error_holder = [None]
    done = threading.Event()

    def worker():
        try:
            result_holder[0] = fn(*args, **kwargs)
        except Exception as e:
            error_holder[0] = e
        finally:
            done.set()

    t = threading.Thread(target=worker, daemon=True)
    t.start()

    with st.spinner(label):
        done.wait()

    t.join()

    if error_holder[0]:
        raise error_holder[0]
    return result_holder[0]


def get_used_titles() -> list:
    log = load_log()
    return [e.get("topic_title", "") for e in log if e.get("topic_title")]


# ── 세션 상태 초기화 ───────────────────────────────────────────────────────────

def init_state():
    defaults = {
        "step": 1,
        "topic_options": None,      # Gemini가 생성한 5개 주제 리스트
        "selected_topic": None,     # 사용자가 선택한 주제 dict
        "script_data": None,        # Gemini 생성 스크립트 (segments 포함)
        "edited_script": None,      # 사용자 편집본
        "image_paths": None,        # 세그먼트별 이미지 경로 리스트
        "luma_video_path": None,    # Luma AI 생성 hook 영상
        "tts_result": None,         # {audio_path, duration_seconds}
        "video_path": None,         # 최종 합성 영상
        "youtube_id": None,
        "regen_index": None,        # 개별 이미지 재생성 인덱스
    }
    for k, v in defaults.items():
        if k not in st.session_state:
            st.session_state[k] = v


init_state()
setup_dirs()
cfg = load_config()

# ── 네비게이션 콜백 (on_click 방식 — 이중 리런 방지) ──────────────────────────────

_RESET_KEYS = ["topic_options", "selected_topic", "script_data", "edited_script",
               "image_paths", "luma_video_path", "tts_result", "video_path", "youtube_id"]


def _reset_to_step1():
    for k in _RESET_KEYS:
        st.session_state[k] = None
    st.session_state.step = 1


def _select_topic(t):
    st.session_state.selected_topic = t
    st.session_state.step = 2


def _back_to_step1():
    st.session_state.step = 1
    st.session_state.script_data = None


def _regen_script():
    st.session_state.script_data = None


def _go_to_step3():
    st.session_state.step = 3


def _back_to_step2():
    st.session_state.step = 2


def _reset_images():
    st.session_state.image_paths = None


def _go_to_step4():
    st.session_state.step = 4


def _back_to_step3():
    st.session_state.video_path = None
    st.session_state.tts_result = None
    st.session_state.luma_video_path = None
    st.session_state.step = 3


def _redo_video():
    st.session_state.video_path = None
    st.session_state.tts_result = None
    st.session_state.luma_video_path = None


def _go_to_step5():
    st.session_state.step = 5


def _trigger_regen(i):
    """개별 이미지 재생성 트리거 (on_click 콜백)"""
    st.session_state.regen_index = i


def _run_full_pipeline_auto(cfg):
    """주제 선택 → 대본 → 이미지 → 영상 → YouTube 업로드 완전 자동 실행."""
    with st.status("🚀 완전 자동 실행 중...", expanded=True) as status:
        try:
            date_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

            # ── 1단계: 주제 자동 선택 ────────────────────────────────────────
            st.write("🎲 주제 자동 선택 중...")
            from modules.script_generator import generate_topics_dynamic
            topics = generate_topics_dynamic(
                api_key=cfg["gemini"]["api_key"],
                model=cfg["gemini"]["model"],
                used_titles=get_used_titles(),
                count=1,
            )
            topic = topics[0]
            st.write(f"✅ 주제: **{topic['title']}**")

            # ── 2단계: 대본 생성 ──────────────────────────────────────────────
            st.write("📝 대본 생성 중...")
            from modules.script_generator import generate_script
            script_data = generate_script(
                topic=topic,
                api_key=cfg["gemini"]["api_key"],
                model=cfg["gemini"]["model"],
            )
            st.write("✅ 대본 완료")

            # ── 3단계: 이미지 생성 ────────────────────────────────────────────
            st.write("🎨 이미지 생성 중...")
            from modules.image_generator import generate_segment_images
            style = cfg.get("image", {}).get("style_prefix",
                "3D Pixar animation style, vibrant colors, friendly characters")
            imagen_model = cfg["gemini"].get("imagen_model", "imagen-4.0-fast-generate-001")
            scenes = generate_segment_images(
                script_data=script_data,
                api_key=cfg["gemini"]["api_key"],
                imagen_model=imagen_model,
                style_prefix=style,
                output_dir=cfg.get("image", {}).get("output_dir", "output/images"),
            )
            ok_count = sum(1 for s in scenes if not s.get("error"))
            st.write(f"✅ 이미지 {ok_count}/{len(scenes)}장 완료")

            # ── 4단계: Luma 인트로 (선택적) ──────────────────────────────────
            luma_key = cfg.get("luma", {}).get("api_key", "")
            imgbb_key = cfg.get("luma", {}).get("imgbb_api_key", "")
            luma_video_path = None
            first_scene = scenes[0] if scenes else None
            hook_img = (first_scene.get("path") if isinstance(first_scene, dict) else first_scene) if first_scene else None
            if luma_key and imgbb_key and hook_img and os.path.exists(hook_img):
                st.write("🎬 Luma AI 인트로 영상 생성 중...")
                from modules.luma_client import image_to_video
                try:
                    luma_video_path = image_to_video(
                        image_path=hook_img,
                        api_key=luma_key,
                        prompt=cfg.get("luma", {}).get("prompt_suffix", "Smooth cinematic camera movement"),
                        output_dir=cfg["output"].get("luma_dir", "output/luma"),
                        imgbb_api_key=imgbb_key,
                    )
                    st.write("✅ Luma 인트로 완료")
                except Exception as e:
                    st.write(f"⚠️ Luma 생성 실패 (정적 이미지로 대체): {e}")

            # ── 5단계: TTS + 영상 합성 ────────────────────────────────────────
            st.write("🎙 TTS 음성 생성 중...")
            from modules.tts_generator import generate_tts
            audio_path = os.path.join(cfg["output"]["audio_dir"], f"tts_{date_str}.mp3")
            full_script = script_data.get("full_script", " ".join(
                s.get("text", "") for s in script_data.get("segments", [])
            ))
            tts_result = generate_tts(
                script_text=full_script,
                output_path=audio_path,
                api_key=cfg["openai"]["api_key"],
                model=cfg["openai"]["tts_model"],
                voice=cfg["openai"]["tts_voice"],
                speed=cfg["openai"]["tts_speed"],
            )
            st.write(f"✅ 음성 완료 ({tts_result['duration_seconds']:.1f}초)")

            st.write("🎬 영상 합성 중...")
            from modules.video_builder import build_video_from_scenes
            video_path = os.path.join(cfg["output"]["video_dir"], f"shorts_{date_str}.mp4")
            build_video_from_scenes(
                scenes=scenes,
                audio_path=tts_result["audio_path"],
                audio_duration=tts_result["duration_seconds"],
                output_path=video_path,
                font_path=cfg["video"]["font_path"],
                luma_video_path=luma_video_path or None,
            )
            st.write("✅ 영상 합성 완료")

            # ── 6단계: YouTube 업로드 ─────────────────────────────────────────
            st.write("🚀 YouTube 업로드 중...")
            from modules.youtube_uploader import get_authenticated_service, upload_video
            client_secret = cfg["youtube"]["client_secrets_file"]
            token_path = cfg["youtube"].get("token_path", "token.pickle")
            yt_service = get_authenticated_service(client_secret, token_path=token_path)
            yt_title = cfg["youtube"]["title_template"].format(topic_title=topic["title"])
            yt_desc = cfg["youtube"]["description_template"].format(
                description=script_data.get("description", "")
            )
            yt_tags = list(dict.fromkeys(
                cfg["youtube"]["default_tags"] + script_data.get("tags", [])
            ))[:15]
            response = upload_video(
                youtube=yt_service,
                file_path=video_path,
                title=yt_title,
                description=yt_desc,
                tags=yt_tags,
                category_id=cfg["youtube"]["category_id"],
                privacy_status="private",
            )
            youtube_id = response.get("id", "unknown")

            # 이력 저장
            save_log({
                "timestamp": datetime.datetime.now().isoformat(),
                "topic_title": topic["title"],
                "category": topic.get("category", ""),
                "output_path": video_path,
                "youtube_video_id": youtube_id,
                "privacy": "private",
            })

            status.update(label="✅ 자동 실행 완료!", state="complete")
            yt_url = f"https://youtube.com/shorts/{youtube_id}"
            st.success(f"🎉 업로드 완료! [{yt_url}]({yt_url})")
            st.info("현재 **비공개(private)** 상태입니다. YouTube Studio에서 확인 후 공개로 변경하세요.")

        except Exception as e:
            status.update(label="❌ 오류 발생", state="error")
            st.error(f"자동 실행 실패: {e}")


# ── 사이드바 ────────────────────────────────────────────────────────────────────
with st.sidebar:
    st.title("🎬 Shorts 자동 생성기")
    st.markdown("제품개발 정보성 YouTube Shorts")
    st.divider()

    page = st.radio(
        "메뉴",
        ["🎬 영상 만들기", "⚙️ 설정", "📋 히스토리"],
        label_visibility="collapsed",
    )

    if page == "🎬 영상 만들기":
        st.divider()
        step = st.session_state.step
        steps = ["주제 선택", "대본 검토", "이미지 확인", "영상 미리보기", "YouTube 업로드"]
        for i, label in enumerate(steps, 1):
            icon = "✅" if i < step else ("▶️" if i == step else "⬜")
            st.markdown(f"{icon} **{i}. {label}**" if i == step else f"{icon} {i}. {label}")

        if step > 1:
            st.divider()
            st.button("↩ 처음부터 다시", use_container_width=True, on_click=_reset_to_step1)


# ══════════════════════════════════════════════════════════════════════════════
# 영상 만들기 (6단계 위자드)
# ══════════════════════════════════════════════════════════════════════════════
if page == "🎬 영상 만들기":

    missing = check_ready(cfg)
    if missing:
        st.error(f"⚠️ 설정 필요: {', '.join(missing)}\n\n[⚙️ 설정] 탭에서 먼저 입력해주세요.")
        st.stop()

    # ────────────────────────────────────────────────────────────────────────
    # STEP 1: 주제 선택
    # ────────────────────────────────────────────────────────────────────────
    if st.session_state.step == 1:
        st.title("📌 Step 1 / 5 — 주제 선택")

        # ── 완전 자동 실행 ─────────────────────────────────────────────────────
        auto_col, _ = st.columns([1, 3])
        with auto_col:
            if st.button("🚀 완전 자동 실행", type="primary", use_container_width=True):
                _run_full_pipeline_auto(cfg)
        st.divider()

        # ── 수동 선택 ──────────────────────────────────────────────────────────
        st.markdown("또는 주제를 직접 선택하세요:")
        col_gen, _ = st.columns([1, 3])
        with col_gen:
            gen_btn = st.button("🎲 주제 5개 생성", use_container_width=True)

        if gen_btn:
            from modules.script_generator import generate_topics_dynamic
            used = get_used_titles()
            try:
                topics = run_with_progress(
                    generate_topics_dynamic,
                    kwargs={"api_key": cfg["gemini"]["api_key"], "model": cfg["gemini"]["model"],
                            "used_titles": used, "count": 5},
                    label="🎲 Gemini가 주제를 생성하는 중...",
                    est_seconds=20,
                )
                st.session_state.topic_options = topics
            except Exception as e:
                st.error(f"❌ 주제 생성 실패: {e}")

        if st.session_state.topic_options:
            st.divider()
            st.markdown(f"**과거 업로드 주제 {len(get_used_titles())}개** 제외하고 추천했습니다.")
            for i, topic in enumerate(st.session_state.topic_options):
                col1, col2 = st.columns([6, 1])
                with col1:
                    category = topic.get("category", "")
                    keywords = topic.get("keywords", [])
                    hook = topic.get("hook_type", "")
                    st.markdown(
                        f"**{i+1}. {topic['title']}**  \n"
                        f"📂 {category} | 훅: `{hook}` | "
                        f"키워드: {' '.join(f'`{k}`' for k in keywords)}"
                    )
                with col2:
                    st.button("선택 ▶", key=f"topic_{i}", use_container_width=True,
                              on_click=_select_topic, args=(topic,))
                st.divider()

    # ────────────────────────────────────────────────────────────────────────
    # STEP 2: 대본 검토
    # ────────────────────────────────────────────────────────────────────────
    elif st.session_state.step == 2:
        st.title("📝 Step 2 / 5 — 대본 검토")
        topic = st.session_state.selected_topic
        st.info(f"**선택 주제**: {topic.get('category', '')} | {topic['title']}")

        # 상단 네비게이션
        _nc1, _nc2, _nc3 = st.columns(3)
        with _nc1:
            st.button("← 주제 다시 선택", key="top_back_s2", on_click=_back_to_step1)
        with _nc2:
            st.button("🔄 대본 다시 생성", key="top_regen_s2", on_click=_regen_script)
        with _nc3:
            st.button("대본 확정 → 이미지 생성 ▶", key="top_next_s2", type="primary", on_click=_go_to_step3)
        st.divider()

        if st.session_state.script_data is None:
            from modules.script_generator import generate_script
            try:
                script_data = run_with_progress(
                    generate_script,
                    kwargs={"topic": topic, "api_key": cfg["gemini"]["api_key"],
                            "model": cfg["gemini"]["model"]},
                    label="📝 Gemini가 대본을 작성하는 중...",
                    est_seconds=20,
                )
                st.session_state.script_data = script_data
                st.session_state.edited_script = script_data.copy()
            except Exception as e:
                st.error(f"❌ 대본 생성 실패: {e}")
                st.stop()

        if st.session_state.script_data is None:
            st.stop()

        script = st.session_state.script_data
        edited = st.session_state.edited_script

        st.subheader("생성된 대본 및 자막")
        col1, col2 = st.columns([3, 2])

        updated_segments = []
        with col1:
            st.markdown("**✏️ 세그먼트 편집** (자막과 영상 대본이 동일하게 적용됩니다)")
            seg_labels = {"hook": "🎬 훅 (도입)", "point": "💡 포인트", "cta": "📢 마무리"}
            for seg in script.get("segments", []):
                label = seg_labels.get(seg["type"], seg["type"])
                if seg["type"] == "point":
                    label += f" {seg.get('index', '')}"
                new_text = st.text_area(
                    label, value=seg["text"], height=90,
                    key=f"seg_{seg['type']}_{seg.get('index',0)}"
                )
                updated_seg = seg.copy()
                updated_seg["text"] = new_text
                updated_segments.append(updated_seg)

            full = " ".join(s["text"] for s in updated_segments)
            _new_edited = {**edited, "segments": updated_segments, "full_script": full}
            if _new_edited != st.session_state.edited_script:
                st.session_state.edited_script = _new_edited

        with col2:
            st.markdown("**📺 영상 자막 미리보기** (실제 영상에 표시될 텍스트)")
            st.markdown("---")
            seg_icons = {"hook": "🎬", "point": "💡", "cta": "📢"}
            for seg in updated_segments:
                icon = seg_icons.get(seg["type"], "▶")
                idx_str = f" {seg.get('index','')}" if seg["type"] == "point" else ""
                with st.container():
                    st.markdown(
                        f"<div style='background:#1a1a2e;border-radius:10px;padding:12px;"
                        f"margin:6px 0;border-left:4px solid #7c83fd;'>"
                        f"<small style='color:#aaa'>{icon}{idx_str}</small><br>"
                        f"<span style='color:#fff;font-size:15px'>{seg['text']}</span></div>",
                        unsafe_allow_html=True
                    )

            chars = len(full)
            est_sec = chars // 5
            st.caption(f"📊 총 {chars}자 | 예상 낭독 시간: 약 {est_sec}초")

        st.divider()
        col_back, col_regen, col_next = st.columns(3)
        with col_back:
            st.button("← 주제 다시 선택", on_click=_back_to_step1)
        with col_regen:
            st.button("🔄 대본 다시 생성", on_click=_regen_script)
        with col_next:
            st.button("대본 확정 → 이미지 생성 ▶", type="primary", on_click=_go_to_step3)

    # ────────────────────────────────────────────────────────────────────────
    # STEP 3: 이미지 생성 · 확인
    # ────────────────────────────────────────────────────────────────────────
    elif st.session_state.step == 3:
        st.title("🎨 Step 3 / 5 — 이미지 생성")
        script = st.session_state.edited_script or st.session_state.script_data
        segments = script.get("segments", [])

        # 상단 네비게이션
        _nc1, _nc2, _nc3 = st.columns(3)
        with _nc1:
            st.button("← 대본으로", key="top_back_s3", on_click=_back_to_step2)
        with _nc2:
            st.button("🔄 전체 재생성", key="top_reset_s3", on_click=_reset_images)
        with _nc3:
            st.button("이미지 확정 → 영상 제작 ▶", key="top_next_s3", type="primary",
                      disabled=(st.session_state.image_paths is None), on_click=_go_to_step4)
        st.divider()

        st.info(
            "Gemini Imagen이 각 세그먼트에 맞는 **3D 픽사 스타일** 이미지를 생성합니다.\n"
            "9:16 비율로 Shorts에 최적화된 해상도로 제작됩니다."
        )

        col_gen, col_info = st.columns([1, 2])
        with col_gen:
            gen_btn = st.button(
                "🎨 이미지 생성",
                type="primary",
                use_container_width=True,
                disabled=(st.session_state.image_paths is not None),
            )
        with col_info:
            est = len(segments) * 2
            st.caption(f"대본 길이에 따라 {len(segments)*1}~{len(segments)*3}장 자동 생성 | 예상 소요: {est*10}~{est*20}초")

        if gen_btn:
            st.session_state.image_paths = None
            from modules.image_generator import generate_segment_images
            style = cfg.get("image", {}).get("style_prefix",
                "3D Pixar animation style, vibrant colors, friendly characters")
            imagen_model = cfg["gemini"].get("imagen_model", "imagen-4.0-fast-generate-001")
            try:
                scenes = run_with_progress(
                    generate_segment_images,
                    kwargs={"script_data": script, "api_key": cfg["gemini"]["api_key"],
                            "imagen_model": imagen_model, "style_prefix": style,
                            "output_dir": cfg.get("image", {}).get("output_dir", "output/images")},
                    label="🎨 Gemini Imagen이 이미지를 생성하는 중...",
                    est_seconds=len(segments) * 15,
                )
                st.session_state.image_paths = scenes
            except Exception as e:
                st.error(f"❌ 이미지 생성 실패: {e}")

        # 개별 재생성 처리 (on_click 콜백으로 트리거된 경우)
        if st.session_state.get("regen_index") is not None:
            ri = st.session_state.regen_index
            st.session_state.regen_index = None
            if st.session_state.image_paths and ri < len(st.session_state.image_paths):
                scene_r = st.session_state.image_paths[ri]
                seg_labels_r = {"hook": "🎬 훅", "point": "💡 포인트", "cta": "📢 마무리"}
                label_r = seg_labels_r.get(scene_r.get("type", "point"), "포인트")
                with st.spinner(f"{label_r} 재생성 중..."):
                    from modules.image_generator import regenerate_single_image
                    style_r = cfg.get("image", {}).get("style_prefix", "3D Pixar animation style")
                    seg_for_regen = {
                        "text": scene_r["text"],
                        "type": scene_r.get("type", "point"),
                        "index": scene_r.get("point_index"),
                    }
                    new_scene = regenerate_single_image(
                        segment=seg_for_regen, index=ri,
                        api_key=cfg["gemini"]["api_key"],
                        imagen_model=cfg["gemini"].get("imagen_model", "imagen-4.0-fast-generate-001"),
                        style_prefix=style_r,
                        output_dir=cfg.get("image", {}).get("output_dir", "output/images"),
                    )
                    if new_scene:
                        updated = list(st.session_state.image_paths)
                        updated[ri] = new_scene
                        st.session_state.image_paths = updated

        if st.session_state.image_paths:
            scenes = st.session_state.image_paths
            fail_count = sum(1 for s in scenes if s.get("error"))
            ok_count = len(scenes) - fail_count

            if fail_count == len(scenes):
                # 전부 실패 — 첫 번째 에러 메시지로 원인 안내
                first_err = next((s["error"] for s in scenes if s.get("error")), "")
                if "429" in first_err or "RESOURCE_EXHAUSTED" in first_err:
                    st.error(
                        f"❌ Imagen API 일일 쿼터 초과 (429 RESOURCE_EXHAUSTED)\n\n"
                        "**해결 방법:**\n"
                        "- 내일 다시 시도 (무료 플랜: 70회/일)\n"
                        "- Google AI Studio에서 과금 설정 후 한도 증가\n"
                        "- 현재는 그라디언트 배경으로 대체되었습니다."
                    )
                else:
                    st.error(f"❌ 이미지 생성 전체 실패: {first_err[:200]}")
            elif fail_count > 0:
                st.warning(f"⚠️ {fail_count}개 이미지 생성 실패 (그라디언트로 대체) | {ok_count}개 성공")
            else:
                st.success(f"✅ {len(scenes)}장 생성 완료!")
            st.divider()

            seg_labels = {"hook": "🎬 훅", "point": "💡 포인트", "cta": "📢 마무리"}
            cols = st.columns(3)
            for i, scene in enumerate(scenes):
                with cols[i % 3]:
                    s_type = scene.get("type", "point")
                    s_pidx = scene.get("point_index", "")
                    label = seg_labels.get(s_type, s_type)
                    if s_type == "point" and s_pidx:
                        label += f" {s_pidx}"
                    st.markdown(f"**{label}**")
                    img_path = scene.get("path", "")
                    if img_path and os.path.exists(img_path):
                        st.image(img_path, use_container_width=True)
                    else:
                        st.warning("이미지 없음")
                    if scene.get("error"):
                        err = scene["error"]
                        if "429" in err or "RESOURCE_EXHAUSTED" in err:
                            st.caption("⚠️ 쿼터 초과 → 그라디언트 대체")
                        else:
                            st.caption(f"⚠️ {err[:80]}")
                    # 개별 재생성 버튼 (on_click 방식)
                    st.button(
                        "🔄 재생성", key=f"regen_{i}",
                        use_container_width=True,
                        on_click=_trigger_regen, args=(i,),
                    )

        st.divider()
        col_back, col_reset, col_next = st.columns(3)
        with col_back:
            st.button("← 대본으로", on_click=_back_to_step2)
        with col_reset:
            st.button("🔄 전체 재생성", on_click=_reset_images)
        with col_next:
            st.button(
                "이미지 확정 → 영상 제작 ▶",
                type="primary",
                disabled=(st.session_state.image_paths is None),
                on_click=_go_to_step4,
            )

    # ────────────────────────────────────────────────────────────────────────
    # STEP 4: 영상 합성 · 미리보기
    # ────────────────────────────────────────────────────────────────────────
    elif st.session_state.step == 4:
        st.title("▶️ Step 4 / 5 — 영상 미리보기")
        script = st.session_state.edited_script or st.session_state.script_data
        topic = st.session_state.selected_topic

        # 상단 네비게이션
        _nc1, _nc2, _nc3 = st.columns(3)
        with _nc1:
            st.button("← 이미지로", key="top_back_s4", on_click=_back_to_step3)
        with _nc2:
            st.button("🔄 영상 다시 합성", key="top_redo_s4", on_click=_redo_video)
        with _nc3:
            st.button("📤 YouTube 업로드 ▶", key="top_next_s4", type="primary", on_click=_go_to_step5)
        st.divider()

        # ── Luma AI: hook 이미지 → 5초 영상 ──────────────────────────────────
        if st.session_state.luma_video_path is None:
            scenes_list = st.session_state.image_paths or []
            luma_key = cfg.get("luma", {}).get("api_key", "")
            first_scene = scenes_list[0] if scenes_list else None
            hook_img = (first_scene["path"] if isinstance(first_scene, dict) else first_scene) if first_scene else None

            imgbb_key = cfg.get("luma", {}).get("imgbb_api_key", "")
            if luma_key and hook_img and os.path.exists(hook_img):
                if not imgbb_key:
                    st.warning("⚠️ imgbb API 키 미설정 — [⚙️ 설정] > Luma AI 탭에서 imgbb API 키를 입력하면 Luma 인트로 영상을 생성할 수 있습니다.")
                    st.session_state.luma_video_path = ""
                else:
                    from modules.luma_client import image_to_video
                    prompt = cfg.get("luma", {}).get(
                        "prompt_suffix",
                        "Smooth cinematic camera movement, professional quality, 3D Pixar style"
                    )
                    try:
                        luma_path = run_with_progress(
                            image_to_video,
                            kwargs={"image_path": hook_img, "api_key": luma_key,
                                    "prompt": prompt,
                                    "output_dir": cfg["output"].get("luma_dir", "output/luma"),
                                    "imgbb_api_key": imgbb_key},
                            label="🎬 Luma AI가 인트로 영상을 생성하는 중...",
                            est_seconds=90,
                        )
                        st.session_state.luma_video_path = luma_path or ""
                    except Exception:
                        st.session_state.luma_video_path = ""
                if st.session_state.luma_video_path:
                    st.success("✅ Luma AI 인트로 영상 생성 완료!")
                else:
                    st.warning("⚠️ Luma AI 생성 실패 → 정적 이미지로 대체됩니다.")
                    st.session_state.luma_video_path = ""
            else:
                st.info("ℹ️ Luma AI 미설정 → 첫 이미지는 정적으로 표시됩니다.")
                st.session_state.luma_video_path = ""

        # ── TTS + 영상 합성 ──────────────────────────────────────────────────
        if st.session_state.video_path is None:
            date_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

            # TTS 생성
            from modules.tts_generator import generate_tts
            audio_path = os.path.join(cfg["output"]["audio_dir"], f"tts_{date_str}.mp3")
            full_script = script.get("full_script", " ".join(
                s.get("text", "") for s in script.get("segments", [])
            ))
            try:
                tts_result = run_with_progress(
                    generate_tts,
                    kwargs={"script_text": full_script, "output_path": audio_path,
                            "api_key": cfg["openai"]["api_key"],
                            "model": cfg["openai"]["tts_model"],
                            "voice": cfg["openai"]["tts_voice"],
                            "speed": cfg["openai"]["tts_speed"]},
                    label="🎙 TTS 음성을 생성하는 중...",
                    est_seconds=15,
                )
                st.session_state.tts_result = tts_result
            except Exception as e:
                st.error(f"❌ TTS 생성 실패: {e}")
                _col1, _col2 = st.columns(2)
                with _col1:
                    st.button("← 이미지로", key="tts_err_back", on_click=_back_to_step3)
                with _col2:
                    st.button("🔄 다시 시도", key="tts_err_redo", on_click=_redo_video)
                st.stop()

            # 영상 합성
            from modules.video_builder import build_video_from_scenes
            video_filename = f"shorts_{date_str}.mp4"
            video_path = os.path.join(cfg["output"]["video_dir"], video_filename)
            try:
                run_with_progress(
                    build_video_from_scenes,
                    kwargs={"scenes": st.session_state.image_paths or [],
                            "audio_path": tts_result["audio_path"],
                            "audio_duration": tts_result["duration_seconds"],
                            "output_path": video_path,
                            "font_path": cfg["video"]["font_path"],
                            "luma_video_path": st.session_state.luma_video_path or None},
                    label="🎬 영상을 합성하는 중...",
                    est_seconds=40,
                )
                st.session_state.video_path = video_path
            except Exception as e:
                st.error(f"❌ 영상 합성 실패: {e}")
                _col1, _col2 = st.columns(2)
                with _col1:
                    st.button("← 이미지로", key="vid_err_back", on_click=_back_to_step3)
                with _col2:
                    st.button("🔄 다시 시도", key="vid_err_redo", on_click=_redo_video)
                st.stop()

        st.success("✅ 영상 합성 완료!")
        tts = st.session_state.tts_result
        if tts:
            st.caption(f"⏱ 영상 길이: {tts['duration_seconds']:.1f}초")

        st.video(st.session_state.video_path)

        st.divider()
        col_back, col_redo, col_next = st.columns(3)
        with col_back:
            st.button("← 이미지로", on_click=_back_to_step3)
        with col_redo:
            st.button("🔄 영상 다시 합성", on_click=_redo_video)
        with col_next:
            st.button("📤 YouTube 업로드 ▶", type="primary", on_click=_go_to_step5)

    # ────────────────────────────────────────────────────────────────────────
    # STEP 5: YouTube 업로드
    # ────────────────────────────────────────────────────────────────────────
    elif st.session_state.step == 5:
        st.title("🚀 Step 5 / 5 — YouTube 업로드")
        try:
            script = st.session_state.edited_script or st.session_state.script_data
            topic = st.session_state.selected_topic

            yt_title = cfg["youtube"]["title_template"].format(topic_title=topic["title"])
            yt_desc = cfg["youtube"]["description_template"].format(
                description=script.get("description", "") if isinstance(script, dict) else ""
            )
            yt_tags = list(dict.fromkeys(
                cfg["youtube"]["default_tags"] + (script.get("tags", []) if isinstance(script, dict) else [])
            ))[:15]
        except Exception as e:
            st.error(f"Step 5 초기화 오류: {e}")
            st.stop()

        if st.session_state.youtube_id is None:
            client_secret = cfg["youtube"]["client_secrets_file"]
            if not os.path.exists(client_secret):
                st.error(
                    f"❌ `{client_secret}` 파일이 없습니다.\n\n"
                    "[⚙️ 설정] → 📺 YouTube 탭에서 발급 방법을 확인하세요."
                )
                st.stop()

            with st.spinner("YouTube에 업로드 중... (1~3분 소요)"):
                from modules.youtube_uploader import get_authenticated_service, upload_video
                token_path = cfg["youtube"].get("token_path", "token.pickle")
                yt_service = get_authenticated_service(client_secret, token_path=token_path)
                response = upload_video(
                    youtube=yt_service,
                    file_path=st.session_state.video_path,
                    title=yt_title,
                    description=yt_desc,
                    tags=yt_tags,
                    category_id=cfg["youtube"]["category_id"],
                    privacy_status="private",  # 항상 비공개
                )
            youtube_id = response.get("id", "unknown")
            st.session_state.youtube_id = youtube_id

            # 이력 저장
            save_log({
                "timestamp": datetime.datetime.now().isoformat(),
                "topic_title": topic["title"],
                "category": topic.get("category", ""),
                "output_path": st.session_state.video_path,
                "youtube_video_id": youtube_id,
                "privacy": "private",
            })

            st.rerun()

        else:
            youtube_id = st.session_state.youtube_id
            yt_url = f"https://youtube.com/shorts/{youtube_id}"
            st.balloons()
            st.success("## 🎉 YouTube 업로드 완료!")
            st.markdown(f"### 🔗 [{yt_url}]({yt_url})")
            st.info("현재 **비공개(private)** 상태입니다. YouTube Studio에서 확인 후 공개로 변경하세요.")

            st.button("새 영상 만들기 →", type="primary", on_click=_reset_to_step1)


# ══════════════════════════════════════════════════════════════════════════════
# 설정 페이지
# ══════════════════════════════════════════════════════════════════════════════
elif page == "⚙️ 설정":
    st.title("⚙️ 설정")

    tab1, tab2, tab3, tab4 = st.tabs(
        ["🤖 Gemini AI", "🎬 Luma AI", "🔊 TTS (OpenAI)", "📺 YouTube"]
    )

    with tab1:
        st.subheader("Google Gemini API")
        st.markdown(
            "API 키 발급: [aistudio.google.com](https://aistudio.google.com) → **Get API key**"
        )
        api_key = st.text_input("Gemini API 키", value=cfg["gemini"]["api_key"], type="password", placeholder="AIza...")
        model_opts = ["gemini-2.5-flash", "gemini-2.5-pro"]
        cur_m = cfg["gemini"]["model"]
        model = st.selectbox("텍스트 모델", model_opts,
                             index=model_opts.index(cur_m) if cur_m in model_opts else 0)
        imagen_opts = ["imagen-4.0-fast-generate-001", "imagen-4.0-generate-001", "imagen-4.0-ultra-generate-001"]
        cur_img = cfg["gemini"].get("imagen_model", "imagen-4.0-fast-generate-001")
        imagen_model = st.selectbox("이미지 모델 (Imagen)", imagen_opts,
                                    index=imagen_opts.index(cur_img) if cur_img in imagen_opts else 0)
        st.caption("💡 imagen-3.0-generate-002: 고품질 | fast: 빠르지만 품질 낮음")
        if st.button("Gemini 설정 저장", type="primary"):
            cfg["gemini"]["api_key"] = api_key
            cfg["gemini"]["model"] = model
            cfg["gemini"]["imagen_model"] = imagen_model
            save_config(cfg)
            st.success("저장 완료!")

    with tab2:
        st.subheader("Luma AI Dream Machine (이미지 → 영상)")
        st.markdown(
            "API 키 발급: [lumalabs.ai](https://lumalabs.ai/dream-machine/api) → **API Keys**"
        )
        luma_key = st.text_input("Luma API 키", value=cfg.get("luma", {}).get("api_key", ""),
                                  type="password", placeholder="luma-...")
        luma_prompt = st.text_input(
            "영상 스타일 프롬프트",
            value=cfg.get("luma", {}).get("prompt_suffix",
                "Smooth cinematic camera movement, professional quality, 3D Pixar style")
        )

        st.divider()
        st.markdown("**🖼 imgbb API 키** (로컬 이미지 → 공개 URL 변환용, Luma 필수)")
        st.markdown(
            "무료 발급: [api.imgbb.com](https://api.imgbb.com) → **Get API key** (무료, 초당 1,000회)"
        )
        imgbb_key = st.text_input(
            "imgbb API 키", value=cfg.get("luma", {}).get("imgbb_api_key", ""),
            type="password", placeholder="abc123..."
        )
        st.caption("💡 Luma AI는 공개 URL만 지원합니다. imgbb가 로컬 이미지를 10분간 임시 공개 URL로 변환합니다.")

        if st.button("Luma 설정 저장", type="primary"):
            if "luma" not in cfg:
                cfg["luma"] = {}
            cfg["luma"]["api_key"] = luma_key
            cfg["luma"]["prompt_suffix"] = luma_prompt
            cfg["luma"]["imgbb_api_key"] = imgbb_key
            save_config(cfg)
            st.success("저장 완료!")

    with tab3:
        st.subheader("OpenAI TTS (음성 생성)")
        st.markdown("Gemini 대본을 자연스러운 한국어 음성으로 변환합니다.")
        oai_key = st.text_input("OpenAI API 키", value=cfg["openai"]["api_key"], type="password")
        voice = st.selectbox("음성", ["nova", "alloy", "echo", "fable", "onyx", "shimmer"],
                             index=["nova","alloy","echo","fable","onyx","shimmer"].index(cfg["openai"]["tts_voice"]))
        speed = st.slider("속도", 0.8, 1.5, cfg["openai"]["tts_speed"], 0.05)
        st.caption("💡 nova: 자연스러운 여성 음성 (한국어 추천)")
        if st.button("TTS 설정 저장", type="primary"):
            cfg["openai"]["api_key"] = oai_key
            cfg["openai"]["tts_voice"] = voice
            cfg["openai"]["tts_speed"] = speed
            save_config(cfg)
            st.success("저장 완료!")

    with tab4:
        st.subheader("YouTube 업로드 설정")

        secret_path = cfg["youtube"]["client_secrets_file"]
        secret_ok = os.path.exists(secret_path)

        if secret_ok:
            st.success("✅ client_secret.json 확인됨")
            token_ok = os.path.exists("token.pickle")
            if token_ok:
                st.success("✅ 인증 토큰(token.pickle) 확인됨 — 업로드 바로 가능")
            else:
                st.warning("⚠️ 아직 로그인 안 됨 — 첫 업로드 시 브라우저에서 Google 계정 로그인 필요")
            if st.button("🗑 인증 토큰 초기화 (재로그인)", key="del_token"):
                if os.path.exists("token.pickle"):
                    os.remove("token.pickle")
                    st.success("token.pickle 삭제됨. 다음 업로드 시 재로그인합니다.")
        else:
            st.error("❌ client_secret.json 없음 — 아래 안내에 따라 파일을 업로드하세요.")

        st.divider()
        st.markdown("**📥 client_secret.json 업로드**")
        uploaded_secret = st.file_uploader(
            "Google Cloud Console에서 다운로드한 OAuth JSON 파일 선택",
            type=["json"],
            key="secret_uploader",
        )
        if uploaded_secret:
            content = uploaded_secret.read()
            # JSON 유효성 확인
            try:
                parsed = json.loads(content)
                if "installed" in parsed or "web" in parsed:
                    with open(secret_path, "wb") as f:
                        f.write(content)
                    # 기존 토큰 삭제 (새 인증 정보이므로)
                    if os.path.exists("token.pickle"):
                        os.remove("token.pickle")
                    st.success(f"✅ {secret_path} 저장 완료! 다음 업로드 시 브라우저 로그인이 진행됩니다.")
                    st.rerun()
                else:
                    st.error("올바른 OAuth 클라이언트 JSON 파일이 아닙니다.")
            except Exception:
                st.error("JSON 파싱 실패. 올바른 파일인지 확인하세요.")

        st.divider()
        with st.expander("📋 client_secret.json 발급 방법"):
            st.markdown("""
1. [console.cloud.google.com](https://console.cloud.google.com) 접속 → **새 프로젝트** 생성
2. 좌측 메뉴 → **API 및 서비스** → **라이브러리** → `YouTube Data API v3` 검색 → **사용 설정**
3. **OAuth 동의 화면** 설정:
   - 사용자 유형: **외부** → 만들기
   - 앱 이름 입력 → **저장 후 계속**
   - **테스트 사용자** 탭 → 본인 Google 계정 이메일 추가
4. **사용자 인증 정보** → **+ 사용자 인증 정보 만들기** → **OAuth 클라이언트 ID**
   - 애플리케이션 유형: **데스크톱 앱** → 만들기
5. **JSON 다운로드** → 위 업로드 버튼으로 파일 선택
            """)

        st.info("📌 업로드는 항상 **비공개(private)**로 진행됩니다. 확인 후 YouTube Studio에서 공개로 변경하세요.")


# ══════════════════════════════════════════════════════════════════════════════
# 히스토리 페이지
# ══════════════════════════════════════════════════════════════════════════════
elif page == "📋 히스토리":
    st.title("📋 생성 히스토리")
    log = load_log()

    if not log:
        st.info("아직 생성된 영상이 없습니다.")
    else:
        st.metric("총 생성 영상", len(log))
        st.divider()
        for entry in reversed(log):
            ts = entry.get("timestamp", "")[:19].replace("T", " ")
            title = entry.get("topic_title", "-")
            yt_id = entry.get("youtube_video_id", "-")
            vpath = entry.get("output_path", "")
            privacy = entry.get("privacy", "private")
            with st.container():
                col1, col2 = st.columns([3, 1])
                with col1:
                    st.markdown(f"**{ts}** | {privacy}  \n📌 {title}")
                    if yt_id and yt_id not in ("unknown", "-"):
                        st.markdown(f"🔗 [YouTube Shorts]({f'https://youtube.com/shorts/{yt_id}'})")
                with col2:
                    if vpath and os.path.exists(vpath):
                        st.video(vpath)
                st.divider()
