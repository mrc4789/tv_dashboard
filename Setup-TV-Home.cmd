@echo off
cd /d "%~dp0"
where py >nul 2>nul
if not errorlevel 1 (
    py -3 tools\home_setup_gui.py
) else (
    python tools\home_setup_gui.py
)
if errorlevel 1 (
    echo Can Python 3 co Tkinter. Khong can chay voi quyen Administrator.
    pause
)
