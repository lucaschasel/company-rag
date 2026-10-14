@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-dev.ps1"
if errorlevel 1 (
  echo Stop failed. Review the error above.
  pause
  exit /b 1
)
exit /b 0
