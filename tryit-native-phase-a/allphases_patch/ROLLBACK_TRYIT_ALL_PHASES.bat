@echo off
setlocal
cd /d "%~dp0"
title TRYIT Native All Phases - Safe Server Update
where py >nul 2>&1
if %errorlevel%==0 (py -3 update_all_phases.py --rollback %* & goto :end)
where python >nul 2>&1
if %errorlevel%==0 (python update_all_phases.py --rollback %* & goto :end)
echo Python 3 not found. Use the OMS server PC.
:end
pause
endlocal
