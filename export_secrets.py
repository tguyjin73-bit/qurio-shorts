"""
GitHub Actions에 올릴 YouTube 인증 파일을 Base64로 인코딩해 출력.

사용법:
  python export_secrets.py

출력된 값을 GitHub 저장소 Settings > Secrets에 추가:
  YOUTUBE_TOKEN_B64      <- token.pickle 인코딩 값
  YOUTUBE_CLIENT_SECRET_B64 <- client_secret.json 인코딩 값
"""

import base64
import os


def encode_file(path: str, secret_name: str) -> None:
    if not os.path.exists(path):
        print(f"[SKIP] {path} 파일이 없습니다 ({secret_name} 건너뜀)")
        return

    with open(path, "rb") as f:
        encoded = base64.b64encode(f.read()).decode("utf-8")

    print(f"\n{'=' * 60}")
    print(f"GitHub Secret 이름: {secret_name}")
    print(f"{'=' * 60}")
    print(encoded)
    print(f"{'=' * 60}")
    print(f"[완료] {path} → {secret_name} ({len(encoded)} chars)")


if __name__ == "__main__":
    base_dir = os.path.dirname(os.path.abspath(__file__))
    print("YouTube 인증 파일을 GitHub Secret용 Base64로 변환합니다.\n")

    encode_file(os.path.join(base_dir, "token.pickle"),        "YOUTUBE_TOKEN_B64")
    encode_file(os.path.join(base_dir, "client_secret.json"), "YOUTUBE_CLIENT_SECRET_B64")

    print("\n[다음 단계]")
    print("1. GitHub 저장소 > Settings > Secrets and variables > Actions")
    print("2. 'New repository secret' 클릭")
    print("3. 위 출력된 이름과 값을 각각 추가")
    print("4. setup_github.bat 실행해서 코드 push")
