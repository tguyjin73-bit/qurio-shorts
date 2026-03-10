@echo off
:: ──────────────────────────────────────────────────────────────
:: 큐리오 YouTube Shorts 자동화 - Windows 작업 스케줄러 등록
:: 관리자 권한으로 실행하세요 (우클릭 → 관리자로 실행)
:: ──────────────────────────────────────────────────────────────

setlocal

set TASK_NAME=QurieShortsAuto
set BAT_FILE=D:\Python_Gross\쇼츠영상제작_0309\run_auto.bat

echo.
echo  큐리오 YouTube Shorts 자동화 스케줄러 등록
echo  ─────────────────────────────────────────
echo  작업명: %TASK_NAME%
echo  실행파일: %BAT_FILE%
echo  실행시각: 매일 오전 09:00
echo.

:: 기존 작업 삭제 (있다면)
schtasks /delete /tn "%TASK_NAME%" /f >nul 2>&1

:: 새 작업 등록 (로그인 상태에서만 실행)
schtasks /create ^
  /tn "%TASK_NAME%" ^
  /tr "\"%BAT_FILE%\"" ^
  /sc daily ^
  /st 09:00 ^
  /it ^
  /f

if %errorlevel% == 0 (
    echo.
    echo  ✅ 등록 완료! 매일 오전 09:00 자동 실행됩니다.
    echo.
    echo  확인 명령어:  schtasks /query /tn "%TASK_NAME%"
    echo  수동 실행:    schtasks /run /tn "%TASK_NAME%"
    echo  등록 취소:    schtasks /delete /tn "%TASK_NAME%" /f
) else (
    echo.
    echo  ❌ 등록 실패. 관리자 권한으로 다시 실행하세요.
    echo     (우클릭 → 관리자로 실행)
)

echo.
pause
