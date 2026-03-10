import os
import pickle

import google_auth_oauthlib.flow
import googleapiclient.discovery
import googleapiclient.errors
import googleapiclient.http

SCOPES = [
    "https://www.googleapis.com/auth/youtube.upload",
    "https://www.googleapis.com/auth/youtube.readonly",   # 채널 영상 제목 검색용
]
TOKEN_CACHE = "token.pickle"


def get_authenticated_service(client_secrets_file: str = "client_secret.json"):
    """OAuth2 인증 후 YouTube API 서비스 반환. token.pickle 캐시 사용."""
    os.environ["OAUTHLIB_INSECURE_TRANSPORT"] = "1"
    credentials = None

    # 캐시된 토큰 로드
    if os.path.exists(TOKEN_CACHE):
        with open(TOKEN_CACHE, "rb") as f:
            credentials = pickle.load(f)

    # 토큰이 없거나 만료된 경우 재인증
    if not credentials or not credentials.valid:
        if credentials and credentials.expired and credentials.refresh_token:
            from google.auth.transport.requests import Request
            credentials.refresh(Request())
        else:
            flow = google_auth_oauthlib.flow.InstalledAppFlow.from_client_secrets_file(
                client_secrets_file, SCOPES
            )
            credentials = flow.run_local_server(port=0)

        # 토큰 저장
        with open(TOKEN_CACHE, "wb") as f:
            pickle.dump(credentials, f)

    return googleapiclient.discovery.build("youtube", "v3", credentials=credentials)


def upload_video(
    youtube,
    file_path: str,
    title: str,
    description: str,
    tags: list,
    category_id: str,
    privacy_status: str,
) -> dict:
    """YouTube에 영상 업로드 후 응답 반환."""
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
        media_body=googleapiclient.http.MediaFileUpload(
            file_path, chunksize=-1, resumable=True
        ),
    )

    response = None
    while response is None:
        status, response = request.next_chunk()
        if status:
            print(f"  업로드 {int(status.progress() * 100)}% 완료")

    return response
