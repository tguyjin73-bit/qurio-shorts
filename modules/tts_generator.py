"""
TTS 음성 생성 모듈.
- 1순위: ElevenLabs (유료, 최고 자연스러움, api_key + voice_id 있을 때)
- 2순위: edge-tts (Microsoft Edge, 무료, 한국어 고품질)
- 3순위: OpenAI TTS (유료, api_key 있을 때)
"""

import asyncio
import os


# edge-tts 한국어 음성 맵
# 남성: GookMinNeural(캐주얼·일반인), InJoonNeural(표준), BongJinNeural(중저음)
# 여성: SunHiNeural(방송체), SeoHyeonNeural(캐주얼)
EDGE_TTS_VOICE_MAP = {
    "echo":    "ko-KR-GookMinNeural",  # 남성 캐주얼 (일반인 느낌, 기본값)
    "alloy":   "ko-KR-InJoonNeural",   # 남성 표준
    "onyx":    "ko-KR-BongJinNeural",  # 남성 중저음
    "nova":    "ko-KR-SunHiNeural",    # 여성 (방송체)
    "shimmer": "ko-KR-SeoHyeonNeural", # 여성 캐주얼
    "fable":   "ko-KR-InJoonNeural",   # 남성 표준
}
DEFAULT_MALE_VOICE = "ko-KR-GookMinNeural"  # GookMin 없을 경우 InJoon으로 fallback

# ── TTS 발음 전처리 매핑 ──────────────────────────────────────────────────────
# 영문 약어 → 한국어 발음 치환 (추가/수정 가능)
_PRONUNCIATION_MAP = [
    # 3D 관련
    ("3D 프린팅",  "쓰리디 프린팅"),
    ("3D프린팅",   "쓰리디 프린팅"),
    ("3D 프린터",  "쓰리디 프린터"),
    ("3D프린터",   "쓰리디 프린터"),
    ("3D ",        "쓰리디 "),
    ("3D,",        "쓰리디,"),
    ("3D.",        "쓰리디."),
    # 재료
    ("PLA",        "피엘에이"),
    ("ABS",        "에이비에스"),
    ("PETG",       "피이티지"),
    ("TPU",        "티피유"),
    # 설계·제조 용어
    ("CAD",        "캐드"),
    ("CAM",        "캠"),
    ("BOM",        "비오엠"),
    ("DFM",        "디에프엠"),
    ("ECO",        "이씨오"),
    ("EVT",        "이브이티"),
    ("DVT",        "디브이티"),
    ("PVT",        "피브이티"),
    # 전자·펌웨어
    ("PCB",        "피씨비"),
    ("MCU",        "엠씨유"),
    ("MCU,",       "엠씨유,"),
    ("SDK",        "에스디케이"),
    ("API",        "에이피아이"),
    ("GPIO",       "지피아이오"),
    ("UART",       "유아트"),
    ("I2C",        "아이투씨"),
    ("SPI",        "에스피아이"),
    # 3D 출력 방식
    ("FDM",        "에프디엠"),
    ("SLA",        "에스엘에이"),
    ("SLS",        "에스엘에스"),
    ("MJF",        "엠제이에프"),
    # 일반
    ("AI",         "에이아이"),
    ("UX",         "유엑스"),
    ("UI",         "유아이"),
    ("PM",         "피엠"),
    ("QC",         "큐씨"),
    ("QA",         "큐에이"),
]


def preprocess_tts_text(text: str) -> str:
    """TTS 낭독 전 발음 치환 전처리.
    영문 약어·기호를 한국어 발음으로 변환해 자연스러운 읽기를 유도.
    """
    import re
    result = text
    for src, dst in _PRONUNCIATION_MAP:
        # 대소문자 모두 처리, 단어 경계 기준
        result = re.sub(re.escape(src), dst, result, flags=re.IGNORECASE)
    return result


def _get_audio_duration(path: str) -> float:
    """오디오 파일 길이 반환 (초). moviepy 1.x / 2.x 모두 호환."""
    try:
        from moviepy.editor import AudioFileClip   # moviepy 1.x
    except ImportError:
        from moviepy import AudioFileClip          # moviepy 2.x
    clip = AudioFileClip(path)
    duration = clip.duration
    clip.close()
    return duration


def _generate_edge_tts(script_text: str, output_path: str, voice: str = "echo", speed: float = 1.0) -> None:
    """edge-tts로 한국어 음성 생성 (비동기 → 동기 래퍼).
    GookMinNeural 없을 경우 InJoonNeural로 자동 fallback.
    Windows + Streamlit 백그라운드 스레드: SelectorEventLoop 명시적 사용.
    """
    import sys
    import edge_tts

    edge_voice = EDGE_TTS_VOICE_MAP.get(voice, DEFAULT_MALE_VOICE)

    # speed를 edge-tts rate 형식으로 변환 (+10%, -5% 등)
    rate_pct = int((speed - 1.0) * 100)
    rate_str = f"+{rate_pct}%" if rate_pct >= 0 else f"{rate_pct}%"

    async def _run(v: str):
        communicate = edge_tts.Communicate(script_text, v, rate=rate_str)
        await communicate.save(output_path)

    # Windows: SelectorEventLoopPolicy로 ProactorEventLoop 충돌 방지
    # (Streamlit 백그라운드 스레드 + Python 3.14 호환)
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    try:
        # GookMinNeural 시도 → 실패 시 InJoonNeural로 fallback
        try:
            loop.run_until_complete(_run(edge_voice))
        except Exception:
            fallback = "ko-KR-InJoonNeural"
            if edge_voice != fallback:
                loop.run_until_complete(_run(fallback))
    finally:
        loop.close()
        asyncio.set_event_loop(None)


def _generate_elevenlabs_tts(script_text: str, output_path: str,
                              api_key: str, voice_id: str,
                              model_id: str = "eleven_multilingual_v2",
                              stability: float = 0.5,
                              similarity_boost: float = 0.75) -> None:
    """ElevenLabs API로 한국어 음성 생성.
    eleven_multilingual_v2 모델: 한국어 지원, 자연스러운 억양.
    stability 0.5 / similarity_boost 0.75 → 자연스럽고 명확한 발음.
    """
    import requests

    url = f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}"
    headers = {
        "xi-api-key": api_key,
        "Content-Type": "application/json",
        "Accept": "audio/mpeg",
    }
    payload = {
        "text": script_text,
        "model_id": model_id,
        "voice_settings": {
            "stability": stability,
            "similarity_boost": similarity_boost,
        },
    }
    resp = requests.post(url, json=payload, headers=headers, timeout=60)
    if resp.status_code != 200:
        raise RuntimeError(f"ElevenLabs HTTP {resp.status_code}: {resp.text[:200]}")
    with open(output_path, "wb") as f:
        f.write(resp.content)


def generate_tts(
    script_text: str,
    output_path: str,
    api_key: str = "",
    model: str = "tts-1",
    voice: str = "nova",
    speed: float = 1.05,
    elevenlabs_api_key: str = "",
    elevenlabs_voice_id: str = "",
    elevenlabs_model: str = "eleven_multilingual_v2",
) -> dict:
    """
    TTS 음성 생성. ElevenLabs → edge-tts → OpenAI 순으로 시도.

    Returns:
        {"audio_path": str, "duration_seconds": float}
    """
    os.makedirs(os.path.dirname(output_path) if os.path.dirname(output_path) else ".", exist_ok=True)

    # 발음 전처리 (CAD→캐드, 3D→쓰리디 등)
    script_text = preprocess_tts_text(script_text)

    elevenlabs_error = None
    edge_error = None
    openai_error = None

    # 1순위: ElevenLabs (api_key + voice_id 있을 때)
    if elevenlabs_api_key and elevenlabs_voice_id:
        try:
            _generate_elevenlabs_tts(
                script_text, output_path,
                api_key=elevenlabs_api_key,
                voice_id=elevenlabs_voice_id,
                model_id=elevenlabs_model,
            )
            duration = _get_audio_duration(output_path)
            print(f"[TTS] ElevenLabs 생성 완료: {output_path} ({duration:.1f}초)")
            return {"audio_path": output_path, "duration_seconds": duration}
        except Exception as e:
            elevenlabs_error = str(e)
            print(f"[TTS] ElevenLabs 실패: {elevenlabs_error}, edge-tts로 재시도")

    # 2순위: edge-tts (무료)
    try:
        _generate_edge_tts(script_text, output_path, voice=voice, speed=speed)
        duration = _get_audio_duration(output_path)
        print(f"[TTS] edge-tts 생성 완료: {output_path} ({duration:.1f}초)")
        return {"audio_path": output_path, "duration_seconds": duration}
    except Exception as e:
        edge_error = str(e)
        print(f"[TTS] edge-tts 실패: {edge_error}, OpenAI로 재시도")

    # 3순위: OpenAI TTS (api_key 있을 때)
    if api_key:
        try:
            from openai import OpenAI
            client = OpenAI(api_key=api_key)
            response = client.audio.speech.create(
                model=model,
                voice=voice,
                input=script_text,
                response_format="mp3",
                speed=speed,
            )
            # openai SDK 1.x / 2.x 모두 호환
            if hasattr(response, "stream_to_file"):
                response.stream_to_file(output_path)
            elif hasattr(response, "content"):
                with open(output_path, "wb") as f:
                    f.write(response.content)
            else:
                with open(output_path, "wb") as f:
                    for chunk in response.iter_bytes():
                        f.write(chunk)
            duration = _get_audio_duration(output_path)
            print(f"[TTS] OpenAI TTS 생성 완료: {output_path} ({duration:.1f}초)")
            return {"audio_path": output_path, "duration_seconds": duration}
        except Exception as e:
            openai_error = str(e)
            print(f"[TTS] OpenAI TTS 실패: {openai_error}")

    # 모두 실패 — 실제 오류 메시지 포함
    parts = []
    if elevenlabs_error:
        parts.append(f"ElevenLabs: {elevenlabs_error[:150]}")
    if edge_error:
        parts.append(f"edge-tts: {edge_error[:150]}")
    if openai_error:
        parts.append(f"OpenAI: {openai_error[:150]}")
    elif not api_key:
        parts.append("OpenAI: API 키 없음")
    raise RuntimeError("모든 TTS 방법 실패:\n" + "\n".join(parts))
