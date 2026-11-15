@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-carbuddy-demo.ps1"
if errorlevel 1 (
    pause
    exit /b 1
)
exit /b 0
