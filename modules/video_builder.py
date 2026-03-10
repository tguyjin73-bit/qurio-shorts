"""
Ken Burns 효과 + 한 줄씩 애니메이션 자막으로 YouTube Shorts 영상 합성.

- 각 씬: zoom_in / zoom_out / pan_left / pan_right 중 랜덤 Ken Burns 효과
- 자막: 텍스트를 줄 단위로 분리 → 시간에 따라 한 줄씩 순차 표시
- 씬 수: image_generator가 대본 길이 기반으로 자동 결정 (제한 없음)
"""

import os
import random
import textwrap
from pathlib import Path

import numpy as np
from moviepy.editor import AudioFileClip, VideoClip, concatenate_videoclips
from PIL import Image, ImageDraw, ImageFont

WIDTH = 1080
HEIGHT = 1920
FPS = 24
EFFECTS = ["zoom_in", "zoom_out", "pan_right", "pan_left"]


def _get_font(font_path: str, size: int):
    try:
        return ImageFont.truetype(font_path, size)
    except Exception:
        return ImageFont.load_default()


def _ken_burns_frame(pil_img, t, dur, effect):
    """시간 t 에서의 Ken Burns 프레임 반환 (numpy array RGB)."""
    W, H = pil_img.size
    progress = min(t / max(dur, 0.01), 1.0)
    margin = 0.10

    if effect == "zoom_in":
        scale, tx, ty = 1.0 + margin * progress, 0.5, 0.5
    elif effect == "zoom_out":
        scale, tx, ty = 1.0 + margin * (1.0 - progress), 0.5, 0.5
    elif effect == "pan_right":
        scale, tx, ty = 1.0 + margin, progress, 0.5
    elif effect == "pan_left":
        scale, tx, ty = 1.0 + margin, 1.0 - progress, 0.5
    else:
        scale, tx, ty = 1.0, 0.5, 0.5

    nw, nh = max(int(W * scale), W), max(int(H * scale), H)
    resized = pil_img.resize((nw, nh), Image.LANCZOS)
    x = max(0, min(int((nw - W) * tx), nw - W))
    y = max(0, min(int((nh - H) * ty), nh - H))
    return np.array(resized.crop((x, y, x + W, y + H)).convert("RGB"))


def _subtitle_frame(base_frame, text, t, dur, segment_type, point_index, font_path):
    """
    base_frame 위에 자막 오버레이.
    - 자막 위치: 화면 중하단 (65% 지점) — 이미지 주요 영역 보존
    - 배경 박스: 반투명 (alpha 85) — 이미지가 비쳐 보임
    - 텍스트: 흰색 + 검정 외곽선 (stroke) — 배경 없이도 높은 가독성
    """
    lines = textwrap.wrap(text, width=16) or [text]
    n = len(lines)
    line_idx = min(int(t / (dur / n)), n - 1)
    current_line = lines[line_idx]

    img = Image.fromarray(base_frame).convert("RGBA")

    # 자막 영역 가벼운 그라디언트 (매우 옅게 — 이미지 잘 보임)
    grad_h = 300
    grad = Image.new("RGBA", (WIDTH, grad_h), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for dy in range(grad_h):
        gd.line([(0, dy), (WIDTH, dy)], fill=(0, 0, 0, int(80 * dy / grad_h)))
    img.paste(grad, (0, HEIGHT - grad_h), grad)

    # 배지 설정
    if segment_type == "hook":
        badge_text, badge_color, font_size = "🎬 시작", (210, 40, 40, 200), 78
    elif segment_type == "point":
        badge_text, badge_color, font_size = f"💡 포인트 {point_index}", (30, 110, 215, 200), 75
    else:
        badge_text, badge_color, font_size = "📢 마무리", (40, 170, 65, 200), 72

    font_main = _get_font(font_path, font_size)
    font_badge = _get_font(font_path, 45)

    # 배지 (상단 고정, 반투명)
    badge_w, badge_h_px = 330, 69
    bx, by = (WIDTH - badge_w) // 2, 108
    bl = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bl)
    try:
        bd.rounded_rectangle([bx, by, bx + badge_w, by + badge_h_px], radius=12, fill=badge_color)
    except TypeError:
        bd.rectangle([bx, by, bx + badge_w, by + badge_h_px], fill=badge_color)
    img = Image.alpha_composite(img, bl)
    draw = ImageDraw.Draw(img)
    try:
        bw = draw.textlength(badge_text, font=font_badge)
    except AttributeError:
        bw, _ = draw.textsize(badge_text, font=font_badge)
    draw.text(((WIDTH - bw) / 2, by + 8), badge_text, font=font_badge, fill=(255, 255, 255, 240))

    # ── 자막 텍스트 (화면 65% 지점 — 중하단) ──────────────────────────────────
    text_y = int(HEIGHT * 0.65)

    try:
        lw = draw.textlength(current_line, font=font_main)
    except AttributeError:
        lw, _ = draw.textsize(current_line, font=font_main)
    lx = (WIDTH - lw) / 2
    pad = 27

    # 반투명 배경 박스 (alpha 85 — 이미지가 비쳐 보임)
    box_l = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    box_d = ImageDraw.Draw(box_l)
    try:
        box_d.rounded_rectangle(
            [lx - pad, text_y - 12, lx + lw + pad, text_y + font_size + 12],
            radius=10, fill=(0, 0, 0, 85)
        )
    except TypeError:
        box_d.rectangle(
            [lx - pad, text_y - 12, lx + lw + pad, text_y + font_size + 12],
            fill=(0, 0, 0, 85)
        )
    img = Image.alpha_composite(img, box_l)
    draw = ImageDraw.Draw(img)

    # 외곽선 (stroke) — 8방향 검정 테두리로 배경 없이도 가독성 확보
    for dx, dy in [(-2,0),(2,0),(0,-2),(0,2),(-2,-2),(2,-2),(-2,2),(2,2)]:
        draw.text((lx + dx, text_y + dy), current_line, font=font_main, fill=(0, 0, 0, 230))

    # 본문 (흰색)
    draw.text((lx, text_y), current_line, font=font_main, fill=(255, 255, 255, 255))

    return np.array(img.convert("RGB"))


def _make_scene_clip(img_path, text, segment_type, point_index, dur, font_path, effect):
    """이미지 + Ken Burns + 한 줄 자막 VideoClip."""
    if img_path and os.path.exists(img_path):
        pil = Image.open(img_path).convert("RGB").resize((WIDTH, HEIGHT), Image.LANCZOS)
    else:
        pil = _gradient_fallback()

    # 클로저 변수 캡처
    _p, _t, _st, _pi, _d, _fp, _ef = pil, text, segment_type, point_index, dur, font_path, effect

    def make_frame(t):
        base = _ken_burns_frame(_p, t, _d, _ef)
        return _subtitle_frame(base, _t, t, _d, _st, _pi, _fp)

    return VideoClip(make_frame, duration=dur).set_fps(FPS)


def build_video_from_scenes(
    scenes: list,
    audio_path: str,
    audio_duration: float,
    output_path: str,
    font_path: str = "assets/fonts/NanumGothicBold.ttf",
    luma_video_path: str = None,
    **kwargs,
) -> str:
    """
    씬 리스트 + TTS 오디오 → YouTube Shorts mp4 합성.

    Args:
        scenes: [{"path": str, "text": str, "type": str, "point_index": int|None}]
    """
    Path(os.path.dirname(output_path)).mkdir(parents=True, exist_ok=True)
    if not scenes:
        raise ValueError("scenes가 비어있습니다.")

    # 씬별 표시 시간: 텍스트 길이 비율로 배분, 최소 2.5초
    total_chars = max(sum(len(s["text"]) for s in scenes), 1)
    scene_durs = [max(audio_duration * len(s["text"]) / total_chars, 2.5) for s in scenes]

    # 첫 씬에 Luma 영상 길이 반영
    if luma_video_path and os.path.exists(luma_video_path):
        try:
            from moviepy.editor import VideoFileClip
            tmp = VideoFileClip(luma_video_path)
            scene_durs[0] = max(scene_durs[0], tmp.duration)
            tmp.close()
        except Exception:
            luma_video_path = None

    # Ken Burns 효과 랜덤 배정 (같은 효과 연속 방지)
    effects, prev = [], None
    for _ in scenes:
        ef = random.choice([e for e in EFFECTS if e != prev])
        effects.append(ef)
        prev = ef

    clips = []
    for i, (sc, dur, effect) in enumerate(zip(scenes, scene_durs, effects)):

        # 첫 씬 Luma 영상 처리
        if i == 0 and luma_video_path and os.path.exists(luma_video_path):
            try:
                from moviepy.editor import VideoFileClip, ImageClip
                lc = VideoFileClip(luma_video_path).resize((WIDTH, HEIGHT))
                if lc.duration < dur:
                    freeze = ImageClip(lc.get_frame(lc.duration - 0.05), duration=dur - lc.duration)
                    lc = concatenate_videoclips([lc, freeze])
                lc = lc.subclip(0, dur)
                _lc, _tx, _d, _st, _si, _fp = lc, sc["text"], dur, sc["type"], sc.get("point_index"), font_path

                def make_luma(t, lc=_lc, tx=_tx, d=_d, st=_st, si=_si, fp=_fp):
                    base = lc.get_frame(min(t, lc.duration - 0.01))
                    return _subtitle_frame(base, tx, t, d, st, si, fp)

                clips.append(VideoClip(make_luma, duration=dur).set_fps(FPS))
                continue
            except Exception as e:
                print(f"[VideoBuilder] Luma 처리 실패: {e}")

        clip = _make_scene_clip(
            sc.get("path", ""), sc["text"], sc["type"], sc.get("point_index"),
            dur, font_path, effect
        )
        if i > 0:
            clip = clip.fadein(0.3)
        clips.append(clip)

    final = concatenate_videoclips(clips, method="compose")
    audio = AudioFileClip(audio_path)
    min_dur = min(audio.duration, final.duration)
    final = final.subclip(0, min_dur).set_audio(audio.subclip(0, min_dur))

    final.write_videofile(
        output_path, fps=FPS, codec="libx264",
        audio_codec="aac", bitrate="2000k", preset="fast",
        logger=None, threads=4,
    )
    audio.close()
    final.close()
    print(f"[VideoBuilder] 완료: {output_path}")
    return output_path


def build_video_from_images(script_data, image_paths, audio_path, audio_duration,
                             output_path, font_path="assets/fonts/NanumGothicBold.ttf",
                             luma_video_path=None, overlay_opacity=0.45):
    """하위 호환용: 기존 image_paths (str 또는 dict 리스트) 지원."""
    segments = script_data.get("segments", [])
    scenes = []
    for i, seg in enumerate(segments):
        item = image_paths[i] if i < len(image_paths) else None
        path = item["path"] if isinstance(item, dict) else item
        scenes.append({"path": path, "text": seg["text"],
                        "type": seg.get("type", "point"), "point_index": seg.get("index")})
    return build_video_from_scenes(scenes, audio_path, audio_duration, output_path,
                                   font_path, luma_video_path)


def _gradient_fallback():
    arr = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)
    for y in range(HEIGHT):
        t = y / HEIGHT
        arr[y, :] = [int(20*(1-t)+50*t), int(40*(1-t)+80*t), int(100*(1-t)+140*t)]
    return Image.fromarray(arr)
