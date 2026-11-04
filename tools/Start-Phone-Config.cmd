@echo off
cd /d "%~dp0.."
where py >nul 2>nul
if %errorlevel% equ 0 (
  py -3 tools\phone_bridge.py
) else (
  python tools\phone_bridge.py
)
pause
