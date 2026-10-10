@echo off
setlocal
cd /d "%~dp0"
title TRYIT Native Phase C - Restore Server Files
where py >nul 2>&1
if %errorlevel%==0 (py -3 update_phase_c.py --rollback %* & goto :end)
where python >nul 2>&1
if %errorlevel%==0 (python update_phase_c.py --rollback %* & goto :end)
echo Python 3 not found.
:end
pause
endlocal
