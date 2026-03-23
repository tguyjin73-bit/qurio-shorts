@echo off
chcp 65001 >nul
title 가디언 AI - 로컬 개발환경 자동 설치

echo =====================================================
echo   가디언 AI 앱 - 로컬 개발환경 자동 설치 스크립트
echo =====================================================
echo.

:: 관리자 권한 확인
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [오류] 관리자 권한이 필요합니다.
    echo 이 파일을 우클릭 후 "관리자 권한으로 실행" 하세요.
    pause
    exit /b 1
)

set "BACKUP_DIR=D:\Set_project_Creo\24_APP_DEV\AI도우미 앱개발\0323_DATA"
set "GITHUB_REPO=https://github.com/tguyjin73-bit/qurio-shorts.git"
set "BRANCH=claude/guardian-ai-android-setup-HOHXX"

echo [1/5] 백업 폴더 생성 중...
if not exist "%BACKUP_DIR%" (
    mkdir "%BACKUP_DIR%"
    echo     폴더 생성 완료: %BACKUP_DIR%
) else (
    echo     폴더 이미 존재: %BACKUP_DIR%
)
echo.

echo [2/5] Git 설치 확인 중...
git --version >nul 2>&1
if %errorLevel% neq 0 (
    echo     Git 설치 중... (winget 사용)
    winget install --id Git.Git -e --source winget --accept-package-agreements --accept-source-agreements
    if %errorLevel% neq 0 (
        echo     [오류] Git 자동 설치 실패.
        echo     https://git-scm.com/download/win 에서 직접 설치 후 다시 실행하세요.
        pause
        exit /b 1
    )
    :: PATH 갱신
    call refreshenv >nul 2>&1
    set "PATH=%PATH%;C:\Program Files\Git\bin"
    echo     Git 설치 완료.
) else (
    for /f "tokens=*" %%v in ('git --version') do echo     %%v 이미 설치됨
)
echo.

echo [3/5] Android Studio 설치 확인 중...
set "AS_PATH="
for %%p in (
    "%LOCALAPPDATA%\Programs\Android Studio\bin\studio64.exe"
    "%PROGRAMFILES%\Android\Android Studio\bin\studio64.exe"
    "%PROGRAMFILES(X86)%\Android\Android Studio\bin\studio64.exe"
) do (
    if exist %%p set "AS_PATH=%%~p"
)

if "%AS_PATH%"=="" (
    echo     Android Studio 설치 중... (winget 사용)
    winget install --id Google.AndroidStudio -e --source winget --accept-package-agreements --accept-source-agreements
    if %errorLevel% neq 0 (
        echo     [경고] Android Studio 자동 설치 실패.
        echo     https://developer.android.com/studio 에서 직접 설치하세요.
        echo     설치 후 이 스크립트를 다시 실행하거나 직접 프로젝트를 여세요.
    ) else (
        echo     Android Studio 설치 완료.
    )
) else (
    echo     Android Studio 이미 설치됨: %AS_PATH%
)
echo.

echo [4/5] 코드 다운로드 중...
cd /d "%BACKUP_DIR%"

if exist ".git" (
    echo     기존 저장소 발견. 최신 코드로 업데이트 중...
    git fetch origin
    git checkout %BRANCH%
    git pull origin %BRANCH%
    echo     업데이트 완료.
) else (
    echo     GitHub에서 코드 클론 중...
    git clone -b %BRANCH% %GITHUB_REPO% .
    if %errorLevel% neq 0 (
        echo     [오류] 클론 실패. 인터넷 연결 및 저장소 URL을 확인하세요.
        pause
        exit /b 1
    )
    echo     코드 다운로드 완료.
)
echo.

echo [5/5] Android Studio에서 프로젝트 열기...
set "PROJECT_DIR=%BACKUP_DIR%\guardian-ai"

if not exist "%PROJECT_DIR%" (
    echo     [오류] guardian-ai 폴더를 찾을 수 없습니다: %PROJECT_DIR%
    echo     수동으로 Android Studio에서 해당 폴더를 열어주세요.
    pause
    exit /b 1
)

:: Android Studio 경로 재탐색
set "AS_PATH="
for %%p in (
    "%LOCALAPPDATA%\Programs\Android Studio\bin\studio64.exe"
    "%PROGRAMFILES%\Android\Android Studio\bin\studio64.exe"
    "%PROGRAMFILES(X86)%\Android\Android Studio\bin\studio64.exe"
) do (
    if exist %%p set "AS_PATH=%%~p"
)

if not "%AS_PATH%"=="" (
    echo     Android Studio 실행 중...
    start "" "%AS_PATH%" "%PROJECT_DIR%"
    echo     Android Studio에서 프로젝트가 열립니다.
) else (
    echo     Android Studio를 찾을 수 없습니다.
    echo     Android Studio를 직접 실행하고 아래 폴더를 여세요:
    echo     %PROJECT_DIR%
)

echo.
echo =====================================================
echo   설치 및 설정 완료!
echo =====================================================
echo.
echo   프로젝트 위치: %PROJECT_DIR%
echo.
echo   Android Studio에서:
echo   1. Gradle 동기화 완료 대기 (하단 진행바 확인)
echo   2. 에뮬레이터 또는 실제 기기 연결
echo   3. 상단 [Run] 버튼(▶) 클릭
echo.
pause
