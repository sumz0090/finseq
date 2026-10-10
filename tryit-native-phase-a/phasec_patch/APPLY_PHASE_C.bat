@echo off
setlocal
cd /d "%~dp0"
title TRYIT Native Phase C - Safe Server Patch
where py >nul 2>&1
if %errorlevel%==0 (py -3 update_phase_c.py %* & goto :end)
where python >nul 2>&1
if %errorlevel%==0 (python update_phase_c.py %* & goto :end)
echo Python 3 not found. Use the OMS server PC.
:end
pause
endlocal
