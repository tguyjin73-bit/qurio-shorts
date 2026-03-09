"""
Gemini Imagen 4를 사용한 씬별 이미지 생성 모듈.
- 9:16 비율, 3D 픽사 스타일
- 대본 텍스트 길이에 따라 세그먼트당 1~3개 이미지 자동 생성 (제한 없음)
- 반환: list[dict] — {"path", "text", "type", "point_index"}
"""

import io
import json
import os
import re
from pathlib import Path

from PIL import Image, ImageEnhance

LOGO_PATH = "assets/logo.png"   # 사용자가 배치하는 큐리오 로고 경로


def _apply_logo(img: Image.Image, logo_path: str = LOGO_PATH,
                opacity: float = 0.72, padding: int = 28) -> Image.Image:
    """이미지 우측 상단에 로고 반투명 오버레이.

    - 흰 배경 자동 누끼 처리 (흰색 계열 픽셀 → 투명)
    - opacity: 가시성 유지하면서 주화면 방해 최소화 (0.72 권장)
    - 로고 너비: 이미지 너비의 22% 고정
    """
    if not os.path.exists(logo_path):
        return img  # 로고 없으면 원본 반환

    logo_orig = Image.open(logo_path).convert("RGBA")

    # ── 흰 배경 누끼 ───────────────────────────────────────────────
    r, g, b, a = logo_orig.split()
    r_arr = list(r.getdata())
    g_arr = list(g.getdata())
    b_arr = list(b.getdata())
    a_arr = list(a.getdata())
    threshold = 230  # 이 값 이상이면 흰색으로 간주 → 투명 처리
    new_a = []
    for i, (rv, gv, bv, av) in enumerate(zip(r_arr, g_arr, b_arr, a_arr)):
        if rv >= threshold and gv >= threshold and bv >= threshold:
            new_a.append(0)
        else:
            new_a.append(av)
    a.putdata(new_a)
    logo_orig = Image.merge("RGBA", (r, g, b, a))

    # ── 크기 조정 (이미지 너비의 22%) ────────────────────────────────
    target_w = int(img.width * 0.22)
    ratio = target_w / logo_orig.width
    target_h = int(logo_orig.height * ratio)
    logo = logo_orig.resize((target_w, target_h), Image.LANCZOS)

    # ── 투명도 적용 ───────────────────────────────────────────────
    lr, lg, lb, la = logo.split()
    la = ImageEnhance.Brightness(la).enhance(opacity)
    logo = Image.merge("RGBA", (lr, lg, lb, la))

    # ── 우측 상단 배치 ────────────────────────────────────────────
    base = img.convert("RGBA")
    x = base.width - target_w - padding
    y = padding
    base.paste(logo, (x, y), logo)
    return base.convert("RGB")

STYLE_PREFIX = (
    "3D CGI professional animation, adult characters only, realistic adult proportions, "
    "NO children NO cartoon kids, Korean adult engineers and product developers, "
    "clean modern office or engineering workshop environment, "
    "professional lighting, cinematic composition, high quality render, "
)

GRADIENT_COLORS = [
    ((20, 60, 120), (60, 20, 100)),
    ((20, 100, 80), (40, 40, 120)),
    ((100, 50, 20), (40, 80, 120)),
    ((60, 20, 80), (20, 60, 100)),
    ((30, 80, 50), (80, 30, 80)),
    ((10, 40, 100), (80, 10, 60)),
    ((60, 30, 10), (20, 80, 80)),
]


def _split_text_to_chunks(text: str, max_chars: int = 80) -> list:
    """텍스트를 문장 단위로 분리해 max_chars 기준으로 청크 생성."""
    sentences = re.split(r'(?<=[.!?。])\s*', text.strip())
    sentences = [s.strip() for s in sentences if s.strip()]
    if not sentences:
        return [text]

    chunks, current = [], ""
    for sent in sentences:
        if current and len(current) + len(sent) > max_chars:
            chunks.append(current.strip())
            current = sent
        else:
            current = (current + " " + sent).strip() if current else sent
    if current:
        chunks.append(current.strip())
    return chunks if chunks else [text]


def _num_images_for_segment(text: str) -> int:
    """대본 길이에 따라 해당 세그먼트에서 생성할 이미지 수 결정."""
    n = len(text)
    if n < 60:
        return 1
    elif n < 130:
        return 2
    else:
        return 3


def _text_to_image_prompt(text: str, api_key: str, style_prefix: str = STYLE_PREFIX,
                           variation_hint: str = "") -> str:
    """한국어 텍스트 → 영어 Imagen 프롬프트 변환."""
    from google import genai as new_genai

    client = new_genai.Client(api_key=api_key)
    system = (
        "You are an AI image prompt expert for professional product development content. "
        "Convert the Korean script text into an English image generation prompt. "
        "STRICT RULES: "
        "1. English only, 50 words max. "
        "2. ONLY adult characters (20s-40s Korean engineers/designers/planners). "
        "3. Depict mature professionals only — engineers, designers, product managers. "
        "4. Real-world settings: CAD workstation, injection mold, prototype lab, meeting room, factory floor. "
        "5. Professional and realistic — this is for adult B2B tech audience. "
        f"{variation_hint} "
        'Respond JSON only: {"scene": "..."}'
    )
    try:
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=f"{system}\n\nScript: {text}\n\nJSON only:",
            config=new_genai.types.GenerateContentConfig(
                response_mime_type="application/json",
                max_output_tokens=200,
            ),
        )
        scene = json.loads(response.text).get("scene", text[:80])
    except Exception:
        scene = text[:80]

    return f"{style_prefix}{scene}, vertical 9:16 format, no text overlay"


def _generate_image(client, imagen_model: str, prompt: str) -> Image.Image | None:
    """Imagen API 호출 → PIL Image 반환. 실패 시 예외 그대로 전파."""
    from google.genai import types as genai_types
    response = client.models.generate_images(
        model=imagen_model,
        prompt=prompt,
        config=genai_types.GenerateImagesConfig(
            number_of_images=1,
            aspect_ratio="9:16",
            safety_filter_level="block_low_and_above",
        ),
    )
    if response.generated_images:
        return Image.open(io.BytesIO(response.generated_images[0].image.image_bytes))
    return None


def _gradient_fallback(idx: int = 0) -> Image.Image:
    """그라디언트 대체 이미지."""
    import numpy as np
    c1, c2 = GRADIENT_COLORS[idx % len(GRADIENT_COLORS)]
    arr = np.zeros((1280, 720, 3), dtype=np.uint8)
    for y in range(1280):
        t = y / 1280
        arr[y, :] = [int(c1[j]*(1-t) + c2[j]*t) for j in range(3)]
    return Image.fromarray(arr)


def generate_segment_images(
    script_data: dict,
    api_key: str,
    imagen_model: str = "imagen-4.0-fast-generate-001",
    style_prefix: str = STYLE_PREFIX,
    output_dir: str = "output/images",
) -> list:
    """
    대본 세그먼트별 이미지 생성 (세그먼트당 1~3개, 길이에 따라 자동 결정).

    Returns:
        list[dict]: [{"path": str, "text": str, "type": str, "point_index": int|None}]
    """
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    segments = script_data.get("segments", [])
    if not segments:
        return []

    # Imagen SDK 초기화
    try:
        from google import genai as new_genai
        client = new_genai.Client(api_key=api_key)
        use_imagen = True
    except ImportError:
        client = None
        use_imagen = False

    scenes = []
    img_count = 0  # 전체 이미지 인덱스 (파일명용)

    for seg in segments:
        seg_text = seg["text"]
        seg_type = seg.get("type", "point")
        seg_pidx = seg.get("index")

        # 세그먼트를 텍스트 청크로 분할
        n_images = _num_images_for_segment(seg_text)
        chunks = _split_text_to_chunks(seg_text, max_chars=max(len(seg_text) // n_images + 1, 40))
        # 청크 수가 n_images와 다를 경우 조정
        while len(chunks) < n_images:
            chunks.append(chunks[-1])
        chunks = chunks[:n_images]

        for j, chunk_text in enumerate(chunks):
            variation = f"Focus on aspect {j+1} of {n_images}." if n_images > 1 else ""
            prompt = _text_to_image_prompt(chunk_text, api_key, style_prefix, variation)

            filename = f"img_{seg_type}_{img_count:03d}.png"
            save_path = os.path.join(output_dir, filename)

            # 이미지 생성
            error_msg = None
            img = None
            if use_imagen:
                try:
                    img = _generate_image(client, imagen_model, prompt)
                except Exception as e:
                    error_msg = str(e)
                    print(f"[Imagen] {filename} 실패: {error_msg}")

            if img is None:
                img = _gradient_fallback(img_count)
                print(f"[Imagen] {filename}: 그라디언트 fallback")

            if img.size != (720, 1280):
                img = img.resize((720, 1280), Image.LANCZOS)

            img = _apply_logo(img)  # 우측 상단 로고 오버레이
            img.save(save_path, "PNG")
            print(f"[Imagen] 저장: {save_path}")

            scenes.append({
                "path": save_path,
                "text": chunk_text,
                "type": seg_type,
                "point_index": seg_pidx,
                "error": error_msg,
            })
            img_count += 1

    return scenes


def regenerate_single_image(
    segment: dict,
    index: int,
    api_key: str,
    imagen_model: str = "imagen-4.0-fast-generate-001",
    style_prefix: str = STYLE_PREFIX,
    output_dir: str = "output/images",
) -> dict:
    """단일 세그먼트 이미지 1장 재생성. 반환: scene dict."""
    prompt = _text_to_image_prompt(segment["text"], api_key, style_prefix)

    try:
        from google import genai as new_genai
        client = new_genai.Client(api_key=api_key)
        img = _generate_image(client, imagen_model, prompt)
    except Exception as e:
        print(f"[Imagen] 재생성 실패: {e}")
        img = None

    if img is None:
        img = _gradient_fallback(index)

    if img.size != (720, 1280):
        img = img.resize((720, 1280), Image.LANCZOS)

    img = _apply_logo(img)  # 우측 상단 로고 오버레이
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    save_path = os.path.join(output_dir, f"img_regen_{index:03d}.png")
    img.save(save_path, "PNG")

    return {
        "path": save_path,
        "text": segment["text"],
        "type": segment.get("type", "point"),
        "point_index": segment.get("index"),
    }
