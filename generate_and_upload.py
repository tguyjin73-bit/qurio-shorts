import os
import json
import datetime
import random

from moviepy.editor import TextClip, CompositeVideoClip, ColorClip, AudioFileClip, ImageClip
import google_auth_oauthlib.flow
import googleapiclient.discovery
import googleapiclient.errors


CONFIG_PATH = "config.json"


def load_config():
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def choose_text(source_file):
    with open(source_file, "r", encoding="utf-8") as f:
        lines = [l.strip() for l in f if l.strip()]
    return random.choice(lines) if lines else ""


def make_short(text, output, length_seconds=15, size=(720, 1280), fontsize=60):
    # 배경 컬러 클립
    bg = ColorClip(size, color=(0, 0, 0), duration=length_seconds)
    # 텍스트 렌더링 (ImageMagick이 없으면 PIL로 대체)
    try:
        txt = TextClip(text, fontsize=fontsize, color="white", size=(size[0] - 100, None), method="caption")
    except Exception as e:
        # MoviePy가 ImageMagick을 찾지 못하는 경우 발생
        print("TextClip 생성 실패, PIL로 대체합니다. ImageMagick 설치를 권장합니다.")
        from PIL import Image, ImageDraw, ImageFont

        # 간단한 텍스트 이미지를 생성
        img = Image.new("RGB", (size[0] - 100, size[1] - 200), color=(0, 0, 0))
        draw = ImageDraw.Draw(img)
        try:
            font = ImageFont.truetype("arial.ttf", fontsize)
        except Exception:
            font = ImageFont.load_default()
        draw.multiline_text((10, 10), text, font=font, fill=(255, 255, 255))
        # MoviePy 이미지 클립으로 변환
        import numpy as np
        txt = ImageClip(np.array(img)).set_duration(length_seconds)

    txt = txt.set_position("center").set_duration(length_seconds)
    video = CompositeVideoClip([bg, txt])
    video.write_videofile(output, fps=24, codec="libx264", audio=False)


def get_authenticated_service(client_secrets_file="client_secret.json", scopes=None):
    if scopes is None:
        scopes = ["https://www.googleapis.com/auth/youtube.upload"]
    os.environ["OAUTHLIB_INSECURE_TRANSPORT"] = "1"
    flow = google_auth_oauthlib.flow.InstalledAppFlow.from_client_secrets_file(
        client_secrets_file, scopes
    )
    credentials = flow.run_console()
    return googleapiclient.discovery.build("youtube", "v3", credentials=credentials)


def upload_video(youtube, file_path, title, description, tags, category_id, privacy_status):
    body = {
        "snippet": {
            "title": title,
            "description": description,
            "tags": tags,
            "categoryId": category_id,
        },
        "status": {"privacyStatus": privacy_status},
    }

    request = youtube.videos().insert(
        part=",".join(body.keys()),
        body=body,
        media_body=googleapiclient.http.MediaFileUpload(file_path, chunksize=-1, resumable=True),
    )

    response = None
    while response is None:
        status, response = request.next_chunk()
        if status:
            print(f"업로드 중 {int(status.progress() * 100)}% 완료")
    print("업로드 완료.")
    return response


def main():
    cfg = load_config()
    today = datetime.datetime.now().strftime("%Y-%m-%d")
    text = choose_text(cfg.get("text_source", "quotes.txt"))
    output_file = cfg.get("output_filename", "short.mp4")
    make_short(text, output_file, length_seconds=cfg.get("video_length_seconds", 15))

    service = get_authenticated_service()
    title = cfg.get("title_template", "Short").format(date=today)
    description = cfg.get("description", "").format(date=today)
    upload_video(
        service,
        output_file,
        title,
        description,
        cfg.get("tags", []),
        cfg.get("categoryId", "22"),
        cfg.get("privacyStatus", "public"),
    )


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--daily",
        action="store_true",
        help="예약 모드로 매일 실행",
    )
    parser.add_argument(
        "--time",
        default="08:00",
        help="예약 실행 시각 (HH:MM)",
    )
    args = parser.parse_args()
    if args.daily:
        import schedule
        import time

        print(f"매일 {args.time}에 실행을 예약합니다.")
        schedule.every().day.at(args.time).do(main)
        while True:
            schedule.run_pending()
            time.sleep(60)
    else:
        main()
