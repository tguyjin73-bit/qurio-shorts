"""
Luma AI Dream Machine API 클라이언트.
이미지를 입력받아 5초 AI 동영상을 생성합니다.
"""

import base64
import os
import time
from pathlib import Path

import requests

LUMA_API_BASE = "https://api.lumalabs.ai/dream-machine/v1"


def _upload_image_as_dataurl(image_path: str) -> str:
    """이미지를 base64 data URL로 변환."""
    with open(image_path, "rb") as f:
        data = f.read()
    ext = Path(image_path).suffix.lower().lstrip(".")
    mime = "image/png" if ext == "png" else "image/jpeg"
    b64 = base64.b64encode(data).decode("utf-8")
    return f"data:{mime};base64,{b64}"


def image_to_video(
    image_path: str,
    api_key: str,
    prompt: str = "Smooth cinematic camera movement, professional quality, 3D Pixar style",
    output_dir: str = "output/luma",
    timeout_seconds: int = 300,
    poll_interval: int = 5,
) -> str:
    """
    Luma AI로 이미지 → 5초 동영상 변환.

    Args:
        image_path: 입력 이미지 경로 (PNG/JPG)
        api_key: Luma AI API 키
        prompt: 동영상 스타일 프롬프트
        output_dir: 결과 영상 저장 디렉토리
        timeout_seconds: 최대 대기 시간 (초)
        poll_interval: 폴링 간격 (초)

    Returns:
        다운로드된 mp4 파일 경로. 실패 시 빈 문자열.
    """
    Path(output_dir).mkdir(parents=True, exist_ok=True)

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    # 이미지를 base64 data URL로 변환
    try:
        image_data_url = _upload_image_as_dataurl(image_path)
    except Exception as e:
        print(f"[Luma] 이미지 로드 실패: {e}")
        return ""

    # 생성 요청
    payload = {
        "prompt": prompt,
        "keyframes": {
            "frame0": {
                "type": "image",
                "url": image_data_url,
            }
        },
        "aspect_ratio": "9:16",
        "loop": False,
    }

    try:
        resp = requests.post(
            f"{LUMA_API_BASE}/generations",
            headers=headers,
            json=payload,
            timeout=30,
        )
        resp.raise_for_status()
        gen_data = resp.json()
        gen_id = gen_data.get("id")
        if not gen_id:
            print(f"[Luma] 생성 ID 없음: {gen_data}")
            return ""
        print(f"[Luma] 생성 시작 ID: {gen_id}")
    except Exception as e:
        print(f"[Luma] 생성 요청 실패: {e}")
        return ""

    # 완료 폴링
    start = time.time()
    while time.time() - start < timeout_seconds:
        try:
            status_resp = requests.get(
                f"{LUMA_API_BASE}/generations/{gen_id}",
                headers=headers,
                timeout=15,
            )
            status_data = status_resp.json()
            state = status_data.get("state", "")
            print(f"[Luma] 상태: {state} ({int(time.time() - start)}초 경과)")

            if state == "completed":
                video_url = status_data.get("assets", {}).get("video", "")
                if video_url:
                    return _download_video(video_url, gen_id, output_dir)
                else:
                    print("[Luma] 완료됐지만 영상 URL 없음")
                    return ""

            elif state == "failed":
                reason = status_data.get("failure_reason", "알 수 없는 오류")
                print(f"[Luma] 생성 실패: {reason}")
                return ""

        except Exception as e:
            print(f"[Luma] 상태 확인 오류: {e}")

        time.sleep(poll_interval)

    print(f"[Luma] 시간 초과 ({timeout_seconds}초)")
    return ""


def _download_video(video_url: str, gen_id: str, output_dir: str) -> str:
    """Luma AI 생성 영상 다운로드."""
    save_path = os.path.join(output_dir, f"luma_{gen_id[:8]}.mp4")
    try:
        resp = requests.get(video_url, stream=True, timeout=120)
        resp.raise_for_status()
        with open(save_path, "wb") as f:
            for chunk in resp.iter_content(chunk_size=8192):
                f.write(chunk)
        print(f"[Luma] 영상 저장: {save_path}")
        return save_path
    except Exception as e:
        print(f"[Luma] 다운로드 실패: {e}")
        return ""
