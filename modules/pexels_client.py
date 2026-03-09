"""
Pexels API를 사용한 무료 배경 영상 검색 및 다운로드 모듈.
"""

import os
import requests
from pathlib import Path

PEXELS_VIDEO_SEARCH = "https://api.pexels.com/videos/search"


def search_videos(query: str, api_key: str, per_page: int = 6, orientation: str = "portrait") -> list[dict]:
    """Pexels에서 쿼리로 영상 검색.

    Returns:
        [{"id", "url", "thumbnail", "duration", "width", "height", "download_url"}, ...]
    """
    headers = {"Authorization": api_key}
    params = {
        "query": query,
        "per_page": per_page,
        "orientation": orientation,  # portrait = 세로형
        "size": "medium",
    }

    resp = requests.get(PEXELS_VIDEO_SEARCH, headers=headers, params=params, timeout=15)
    resp.raise_for_status()
    data = resp.json()

    results = []
    for v in data.get("videos", []):
        # 가장 적합한 화질 파일 선택 (HD 720p 우선, 없으면 최고화질)
        files = sorted(v.get("video_files", []), key=lambda f: f.get("width", 0))
        hd_files = [f for f in files if f.get("width", 0) <= 1280]
        chosen = hd_files[-1] if hd_files else (files[-1] if files else None)
        if not chosen:
            continue

        results.append({
            "id": v["id"],
            "url": v.get("url", ""),
            "thumbnail": v.get("image", ""),  # 썸네일 이미지 URL
            "duration": v.get("duration", 0),
            "width": chosen.get("width", 0),
            "height": chosen.get("height", 0),
            "download_url": chosen.get("link", ""),
        })

    return results


def download_video(download_url: str, save_dir: str, video_id: int) -> str:
    """Pexels 영상 다운로드. 이미 있으면 재사용.

    Returns:
        저장된 파일 경로
    """
    Path(save_dir).mkdir(parents=True, exist_ok=True)
    save_path = os.path.join(save_dir, f"pexels_{video_id}.mp4")

    if os.path.exists(save_path):
        return save_path

    resp = requests.get(download_url, stream=True, timeout=60)
    resp.raise_for_status()

    with open(save_path, "wb") as f:
        for chunk in resp.iter_content(chunk_size=1024 * 1024):
            f.write(chunk)

    return save_path
