@echo off
setlocal
where gradle >nul 2>&1
if errorlevel 1 (
  echo Gradle is not installed or not in PATH.
  pause
  exit /b 1
)
gradle --no-daemon :app:assembleDebug
if errorlevel 1 pause
