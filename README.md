# YouTube 쇼츠 자동화

이 프로젝트는 YouTube Data API를 사용해 매일 자동으로 YouTube 쇼츠 영상을 생성하고 채널에 업로드합니다.

## 기능

- `moviepy`로 텍스트, 이미지 또는 클립에서 짧은 영상 생성
- Google API 자격증명으로 YouTube에 비디오 업로드
- Windows 작업 스케줄러 또는 Python `schedule` 라이브러리를 사용한 일일 실행 예약

## 설정 단계

1. **Google Cloud 및 YouTube API 준비**
   - [Google Cloud Console](https://console.cloud.google.com/)에서 프로젝트를 생성합니다.
   - YouTube Data API v3를 활성화합니다.
   - OAuth 2.0 자격증명(또는 서버 측 업로드에 API 키)을 생성합니다.
   - `client_secret.json` 파일을 다운로드하여 프로젝트 루트에 놓습니다.

2. **Python 환경 구성**
   ```sh
   python -m venv venv
   venv\Scripts\activate
   pip install -r requirements.txt
   ```

3. **설정 파일 작성**
   - `config.example.json`을 `config.json`으로 복사하고 제목 템플릿, 설명, 태그 등 필요한 항목을 채웁니다.

4. **수동 실행**
   ```sh
   python generate_and_upload.py
   ```

5. **일정 예약**
   - Windows 작업 스케줄러를 사용하여 매일 스크립트를 실행하거나 `generate_and_upload.py` 내부에 예약 로직을 추가합니다.

     예시 (작업 스케줄러):
     1. 작업 스케줄러 열기 -> 작업 만들기
     2. 트리거 탭에서 "일별" 선택, 시간 설정
     3. 동작 탭에서 "프로그램 시작" 선택
        - 프로그램/스크립트: `python`
        - 인수 추가: `d:\Python_Gross\쇼츠영상제작_0309\generate_and_upload.py`
        - 시작 위치: `d:\Python_Gross\쇼츠영상제작_0309`
     4. 확인을 눌러 저장합니다.

## 요구 사항

`requirements.txt`를 참조하세요.

