@echo off
cd /d "%~dp0"
if exist "%~dp0Machine Vision Camera Controller.exe" (
    start "" "%~dp0Machine Vision Camera Controller.exe"
) else (
    py "%~dp0remote_camera_control.py"
)
