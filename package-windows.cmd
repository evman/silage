@echo off
rem Builds dist\Silage-Portable.exe plus a portable folder and ZIP (no Python needed).
rem The previous dist\Silage and ZIP are moved to dist\backups\before-package-<time> first.
setlocal
set "NEWZIP="
set "NEWEXE="
cd /d "%~dp0"
set "LOG=%~dp0package-windows.log"
echo Packaging Silage... (log: package-windows.log)
echo === package %DATE% %TIME% === > "%LOG%"

tasklist /FI "IMAGENAME eq silage.exe" 2>nul | find /I "silage.exe" >nul
if not errorlevel 1 (
  echo Silage is running. Close it and run this again.
  echo ABORT: silage.exe running >> "%LOG%"
  goto end_fail
)

echo [1/7] Building...
call "%~dp0build-windows.cmd" :app:createDistributable --console=plain %* >> "%LOG%" 2>&1
if errorlevel 1 ( echo Build failed - see package-windows.log & goto end_fail )
set "SRC=app\build\compose\binaries\main\app\silage"
if not exist "%SRC%\silage.exe" ( echo Build output missing: %SRC% >> "%LOG%" & echo Build output missing & goto end_fail )

echo [2/7] Backing up the current dist copy...
for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%T"
set "BACKUP=dist\backups\before-package-%TS%"
mkdir "%BACKUP%" || goto end_fail
if exist "dist\Silage" move "dist\Silage" "%BACKUP%\Silage" >> "%LOG%" 2>&1 || goto restore
if exist "dist\Silage-Windows-x64.zip" move "dist\Silage-Windows-x64.zip" "%BACKUP%\" >> "%LOG%" 2>&1 || goto restore
if exist "dist\Silage-Portable.exe" move "dist\Silage-Portable.exe" "%BACKUP%\" >> "%LOG%" 2>&1 || goto restore
echo Backup: %BACKUP% >> "%LOG%"

echo [3/7] Assembling dist\Silage...
rem Never ship local settings, even if the build output was launched for testing.
robocopy "%SRC%" "dist\Silage" /E /XD userdata /NFL /NDL /NJH /NJS >> "%LOG%" 2>&1
if errorlevel 8 goto restore
copy /y /b "native\libmpv-2.dll" "dist\Silage\" >> "%LOG%" 2>&1 || goto restore
copy /y "LICENSE" "dist\Silage\LICENSE-Silage.txt" >> "%LOG%" 2>&1 || goto restore
copy /y "native\LICENSE-mpv-GPL.txt" "dist\Silage\" >> "%LOG%" 2>&1 || goto restore
copy /y "native\Copyright-mpv.txt" "dist\Silage\" >> "%LOG%" 2>&1 || goto restore
robocopy "native\fallback" "dist\Silage\fallback" /E /NFL /NDL /NJH /NJS >> "%LOG%" 2>&1
if errorlevel 8 goto restore
copy /y "WINDOWS.md" "dist\Silage\" >> "%LOG%" 2>&1 || goto restore
echo Adding yt-dlp and Deno for trailers... >> "%LOG%"
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "scripts\fetch-trailer-tools.ps1" -Out "dist\Silage\tools" >> "%LOG%" 2>&1
if errorlevel 1 ( echo Adding the trailer tools failed - see package-windows.log & goto restore )

echo [4/7] Checking the packaged app finds its bundled mpv...
set "JAVA_TOOL_OPTIONS="
set "JAVA_HOME="
"dist\Silage\silage.exe" --check >> "%LOG%" 2>&1
if errorlevel 1 ( echo silage.exe --check failed - see package-windows.log & goto restore )
"dist\Silage\silage.exe" --version >> "%LOG%" 2>&1

echo [5/7] Creating dist\Silage-Windows-x64.zip...
set "NEWZIP=1"
powershell -NoProfile -Command "Compress-Archive -Path 'dist\Silage' -DestinationPath 'dist\Silage-Windows-x64.zip' -Force" >> "%LOG%" 2>&1
if errorlevel 1 ( echo ZIP failed - see package-windows.log & goto restore )

echo [6/7] Creating and checking the single-file portable EXE...
set "NEWEXE=1"
powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "scripts\package-single-exe.ps1" -Archive "dist\Silage-Windows-x64.zip" -Output "dist\Silage-Portable.exe" >> "%LOG%" 2>&1
if errorlevel 1 goto restore

echo [7/7] Preserving local portable settings...
rem Restore personal data only AFTER creating the clean release ZIP.
if exist "%BACKUP%\Silage\userdata" (
  robocopy "%BACKUP%\Silage\userdata" "dist\Silage\userdata" /E /NFL /NDL /NJH /NJS >> "%LOG%" 2>&1
  if errorlevel 8 goto restore
)

echo PACKAGE OK >> "%LOG%"
echo.
echo Done. Run dist\Silage-Portable.exe
echo Previous copy saved in %BACKUP%
pause
exit /b 0

:restore
echo Restoring the previous dist copy... >> "%LOG%"
rem Remove a new ZIP if a later step failed, including on the first package run.
if defined NEWZIP if exist "dist\Silage-Windows-x64.zip" del /q "dist\Silage-Windows-x64.zip" >> "%LOG%" 2>&1
if defined NEWEXE if exist "dist\Silage-Portable.exe" del /q "dist\Silage-Portable.exe" >> "%LOG%" 2>&1
if exist "%BACKUP%\Silage" (
  if exist "dist\Silage" rmdir /s /q "dist\Silage"
  move "%BACKUP%\Silage" "dist\Silage" >> "%LOG%" 2>&1
)
if exist "%BACKUP%\Silage-Windows-x64.zip" move "%BACKUP%\Silage-Windows-x64.zip" "dist\" >> "%LOG%" 2>&1
if exist "%BACKUP%\Silage-Portable.exe" move "%BACKUP%\Silage-Portable.exe" "dist\" >> "%LOG%" 2>&1
echo Packaging failed; your previous dist copy was put back. See package-windows.log
:end_fail
echo PACKAGE FAILED >> "%LOG%"
pause
exit /b 1
