"""
Luma AI Dream Machine API 클라이언트.
imgbb API로 로컬 이미지를 공개 URL로 변환한 뒤 Luma Ray 2 모델로 5초 영상 생성.
"""

import base64
import os
import time
from pathlib import Path

import requests

IMGBB_API_URL = "https://api.imgbb.com/1/upload"


def _upload_to_imgbb(image_path: str, imgbb_api_key: str, expiration: int = 600) -> str:
    """로컬 이미지를 imgbb에 업로드하고 공개 URL 반환.

    Args:
        image_path:    로컬 이미지 경로 (PNG/JPG)
        imgbb_api_key: imgbb API 키 (https://api.imgbb.com 에서 무료 발급)
        expiration:    URL 유효 시간(초). 기본 600초(10분). 0=영구.

    Returns:
        공개 이미지 URL 문자열
    """
    with open(image_path, "rb") as f:
        b64_data = base64.b64encode(f.read()).decode("utf-8")

    payload = {"key": imgbb_api_key, "image": b64_data}
    if expiration:
        payload["expiration"] = expiration

    resp = requests.post(IMGBB_API_URL, data=payload, timeout=30)
    resp.raise_for_status()
    result = resp.json()

    if not result.get("success"):
        raise RuntimeError(f"imgbb 업로드 실패: {result}")

    url = result["data"]["url"]
    print(f"[imgbb] 업로드 완료: {url[:70]}...")
    return url


def image_to_video(
    image_path: str,
    api_key: str,
    prompt: str = "Smooth cinematic camera movement, professional quality, 3D Pixar style",
    output_dir: str = "output/luma",
    timeout_seconds: int = 300,
    poll_interval: int = 5,
    imgbb_api_key: str = "",
) -> str:
    """
    로컬 이미지 → imgbb 공개 URL → Luma Ray 2 → 5초 mp4 영상.

    Args:
        image_path:       입력 이미지 경로 (PNG/JPG)
        api_key:          Luma AI API 키
        prompt:           동영상 스타일 프롬프트
        output_dir:       결과 영상 저장 디렉토리
        timeout_seconds:  최대 대기 시간(초)
        poll_interval:    폴링 간격(초)
        imgbb_api_key:    imgbb API 키 (로컬 이미지 공개 URL 변환용)

    Returns:
        저장된 mp4 경로. 실패 시 빈 문자열.
    """
    Path(output_dir).mkdir(parents=True, exist_ok=True)

    # ── 1단계: imgbb로 이미지 공개 URL 획득 ─────────────────────────────────
    if not imgbb_api_key:
        print("[Luma] imgbb API 키 없음 — ⚙️ 설정 > Luma AI 탭에서 imgbb API 키를 입력하세요.")
        return ""

    try:
        image_url = _upload_to_imgbb(image_path, imgbb_api_key)
    except Exception as e:
        print(f"[Luma] imgbb 업로드 실패: {e}")
        return ""

    # ── 2단계: Luma SDK로 영상 생성 요청 ────────────────────────────────────
    try:
        from lumaai import LumaAI
        luma_client = LumaAI(auth_token=api_key)

        generation = luma_client.generations.video.create(
            model="ray-flash-2",
            prompt=prompt,
            aspect_ratio="9:16",
            duration="5s",
            keyframes={
                "frame0": {
                    "type": "image",
                    "url": image_url,
                }
            },
        )
        gen_id = generation.id
        print(f"[Luma] 생성 시작 ID: {gen_id}")
    except Exception as e:
        print(f"[Luma] 생성 요청 실패: {e}")
        return ""

    # ── 3단계: 완료 폴링 ─────────────────────────────────────────────────────
    start = time.time()
    while time.time() - start < timeout_seconds:
        try:
            status = luma_client.generations.get(gen_id)
            state = status.state
            print(f"[Luma] 상태: {state} ({int(time.time() - start)}초 경과)")

            if state == "completed":
                video_url = status.assets.video if status.assets else None
                if video_url:
                    return _download_video(video_url, gen_id, output_dir)
                else:
                    print("[Luma] 완료됐지만 영상 URL 없음")
                    return ""

            elif state == "failed":
                reason = getattr(status, "failure_reason", "알 수 없는 오류")
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
