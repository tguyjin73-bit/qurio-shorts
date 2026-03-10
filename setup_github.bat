@echo off
:: ============================================================
:: GitHub 저장소 초기 설정 및 push 스크립트
:: 반드시 아래 순서대로 진행하세요:
::
:: [사전 준비]
:: 1. https://github.com 에서 새 Private 저장소 생성
::    (이름 예: qurio-shorts-auto)
:: 2. GitHub Personal Access Token 발급
::    Settings > Developer settings > Personal access tokens > Fine-grained tokens
::    권한: Contents (read/write), Workflows (read/write)
::
:: [이 스크립트 실행 전]
:: python export_secrets.py  <- 먼저 실행해서 YouTube 토큰 Base64 값 준비
::
:: [실행]
:: setup_github.bat
:: ============================================================

setlocal

set GIT="C:\Program Files\Git\cmd\git.exe"
set REPO_DIR=D:\Python_Gross\쇼츠영상제작_0309

echo.
echo  GitHub 저장소 설정
echo  ==================
echo  저장소 URL을 입력하세요.
echo  예: https://github.com/username/qurio-shorts-auto.git
echo.
set /p REPO_URL="GitHub URL: "

if "%REPO_URL%"=="" (
    echo 저장소 URL을 입력해야 합니다.
    pause
    exit /b 1
)

cd /d %REPO_DIR%

echo.
echo [1/4] git 초기화 확인...
%GIT% init --initial-branch=main 2>nul || %GIT% init

echo [2/4] git remote 설정...
%GIT% remote remove origin 2>nul
%GIT% remote add origin %REPO_URL%

echo [3/4] 파일 스테이징...
%GIT% add .
%GIT% status --short

echo [4/4] 커밋 및 push...
%GIT% commit -m "feat: YouTube Shorts 자동화 파이프라인 초기 설정

- DALL-E 3 이미지 생성 (Imagen 429 fallback)
- edge-tts 한국어 TTS (nova=SunHiNeural)
- GitHub Actions 매일 09:00 KST 자동 실행
- 영상 1개/일 자동 업로드"

%GIT% push -u origin main

if %errorlevel% == 0 (
    echo.
    echo  Push 완료!
    echo.
    echo  [다음 단계 - GitHub Secrets 등록]
    echo  %REPO_URL% 에서 Settings ^> Secrets and variables ^> Actions
    echo.
    echo  필수 Secrets:
    echo    GEMINI_API_KEY
    echo    OPENAI_API_KEY
    echo    ELEVENLABS_API_KEY
    echo    LUMA_API_KEY
    echo    TELEGRAM_BOT_TOKEN
    echo    TELEGRAM_CHAT_ID
    echo    YOUTUBE_TOKEN_B64         ^<- python export_secrets.py 실행값
    echo    YOUTUBE_CLIENT_SECRET_B64 ^<- python export_secrets.py 실행값
    echo.
    echo  [GitHub Actions 테스트]
    echo  저장소 ^> Actions ^> Daily YouTube Shorts Upload ^> Run workflow
) else (
    echo.
    echo  Push 실패. 아래를 확인하세요:
    echo  - GitHub URL이 올바른지 확인
    echo  - Personal Access Token으로 인증 필요
    echo    https://github.com/settings/tokens
)

echo.
pause
