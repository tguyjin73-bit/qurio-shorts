@echo off
chcp 65001 >nul
title 큐리오 Shorts 자동생성기

echo ================================================
echo   큐리오 YouTube Shorts 자동생성기 시작 중...
echo ================================================
echo.

cd /d "%~dp0"

set PYTHON=C:\Users\USER\AppData\Local\Programs\Python\Python314\python.exe
set STREAMLIT=C:\Users\USER\AppData\Local\Programs\Python\Python314\Scripts\streamlit.exe

:: 브라우저 자동 오픈 (2초 후)
start /b cmd /c "timeout /t 2 >nul && start http://localhost:8501"

:: Streamlit 실행
"%STREAMLIT%" run app.py --server.port 8501 --server.headless false --browser.gatherUsageStats false

pause
