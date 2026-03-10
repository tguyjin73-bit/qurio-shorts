"""
GitHub Actions CI 환경에서 환경변수로부터 config.json 생성.
로컬에서는 config.json을 직접 사용하므로 실행 불필요.

사용법 (GitHub Actions workflow 내부):
  python generate_config.py
"""

import json
import os


def main():
    def env(key: str, default: str = "") -> str:
        val = os.environ.get(key, default)
        if not val and not default:
            print(f"[WARNING] 환경변수 {key} 가 설정되지 않았습니다.")
        return val

    config = {
        "gemini": {
            "api_key": env("GEMINI_API_KEY"),
            "model": "gemini-2.5-flash",
            "imagen_model": "imagen-4.0-fast-generate-001"
        },
        "luma": {
            "api_key": env("LUMA_API_KEY"),
            "prompt_suffix": "Smooth cinematic camera movement, professional quality, 3D Pixar style",
            "imgbb_api_key": ""
        },
        "openai": {
            "api_key": env("OPENAI_API_KEY"),
            "tts_model": "tts-1",
            "tts_voice": "nova",
            "tts_speed": 1.05
        },
        "elevenlabs": {
            "api_key": env("ELEVENLABS_API_KEY"),
            "voice_id": "",
            "model": "eleven_multilingual_v2"
        },
        "telegram": {
            "bot_token": env("TELEGRAM_BOT_TOKEN"),
            "chat_id": env("TELEGRAM_CHAT_ID")
        },
        "image": {
            "output_dir": "output/images",
            "style_prefix": (
                "3D CGI professional animation, adult characters only, "
                "realistic adult proportions, NO children NO cartoon kids, "
                "Korean adult engineers and product developers, "
                "clean modern engineering environment, professional lighting, "
                "cinematic composition, high quality render"
            ),
            "dalle3_quality": "standard"
        },
        "video": {
            "width": 1080,
            "height": 1920,
            "fps": 24,
            "codec": "libx264",
            "audio_codec": "aac",
            "bitrate": "4000k",
            "min_duration_seconds": 30,
            "max_duration_seconds": 65,
            "font_path": "assets/fonts/NanumGothicBold.ttf",
            "overlay_opacity": 0.45
        },
        "output": {
            "audio_dir": "output/audio",
            "video_dir": "output/video",
            "luma_dir": "output/luma",
            "filename_template": "shorts_{date}.mp4"
        },
        "youtube": {
            "client_secrets_file": "client_secret.json",
            "category_id": "28",
            "privacy_status": "public",
            "title_template": "{topic_title} #Shorts",
            "description_template": (
                "{description}\n\n구독하면 매일 제품개발 콘텐츠 올라옵니다!\n"
                "#스타트업 #제품개발 #개발자 #하드웨어 #Shorts"
            ),
            "default_tags": ["Shorts", "스타트업", "제품개발", "개발자", "하드웨어"]
        },
        "schedule": {
            "enabled": False,
            "time": "09:00",
            "videos_per_day": 1
        },
        "logging": {
            "log_file": "logs/generation_log.json",
            "level": "INFO"
        }
    }

    with open("config.json", "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)

    print("[generate_config] config.json 생성 완료")
    # 민감한 키 마스킹 출력
    masked = json.loads(json.dumps(config))
    for section in ("gemini", "openai", "elevenlabs", "luma"):
        if masked.get(section, {}).get("api_key"):
            masked[section]["api_key"] = masked[section]["api_key"][:8] + "..."
    print(json.dumps(masked, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
