@echo off
rem Double-click to run the app, player and shared test suites; full output goes to test-windows.log.
cd /d "%~dp0"
echo Running Silage tests (this takes a few minutes)...
call "%~dp0build-windows.cmd" :app:test :player:test :shared:desktopTest --console=plain > test-windows.log 2>&1
set "RC=%ERRORLEVEL%"
findstr /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" /C:"tests completed" /C:" FAILED" test-windows.log
echo.
if "%RC%"=="0" (echo All tests passed.) else (echo Tests failed - see test-windows.log)
pause
exit /b %RC%
