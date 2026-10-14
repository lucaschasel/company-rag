@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch.ps1"
if errorlevel 1 (
  echo Startup failed. Review the error above and .tools logs.
  pause
  exit /b 1
)
exit /b 0
