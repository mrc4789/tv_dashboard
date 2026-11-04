@echo off
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel% equ 0 (
  py -3 backend\run_service.py
) else (
  python backend\run_service.py
)
pause
