@echo off
rem Builds dist\Silage and the per-user installer dist\Silage-<version>-Setup.exe, plus its
rem version-free copy dist\Silage-Setup.exe (no Python needed; Inno Setup 6 required).
rem The previous dist\Silage and installers are moved to dist\backups\before-package-<time> first.
setlocal
rem Started from PowerShell 7 (GitHub's runners), its module path would hide Windows PowerShell's own
rem modules from the powershell steps below; without the variable, Windows PowerShell uses its defaults.
set "PSModulePath="
set "NEWSETUP="
set "VERSION="
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

echo [1/6] Building...
call "%~dp0build-windows.cmd" :app:createDistributable --console=plain %* >> "%LOG%" 2>&1
if errorlevel 1 ( echo Build failed - see package-windows.log & goto end_fail )
set "SRC=app\build\compose\binaries\main\app\silage"
if not exist "%SRC%\silage.exe" ( echo Build output missing: %SRC% >> "%LOG%" & echo Build output missing & goto end_fail )

rem Inno Setup's compiler: on PATH, or where its installer (and choco) puts it.
set "ISCC="
for %%I in (ISCC.exe) do if not "%%~$PATH:I"=="" set "ISCC=%%~$PATH:I"
if not defined ISCC if exist "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" set "ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not defined ISCC if exist "%ProgramFiles%\Inno Setup 6\ISCC.exe" set "ISCC=%ProgramFiles%\Inno Setup 6\ISCC.exe"
if not defined ISCC if exist "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
if not defined ISCC (
  echo Inno Setup 6 is missing. Run scripts\prepare-windows.ps1 or install it from jrsoftware.org.
  echo ABORT: ISCC.exe not found >> "%LOG%"
  goto end_fail
)

echo [2/6] Backing up the current dist copy...
for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%T"
set "BACKUP=dist\backups\before-package-%TS%"
mkdir "%BACKUP%" || goto end_fail
if exist "dist\Silage" move "dist\Silage" "%BACKUP%\Silage" >> "%LOG%" 2>&1 || goto restore
if exist "dist\Silage-Setup.exe" move "dist\Silage-Setup.exe" "%BACKUP%\" >> "%LOG%" 2>&1 || goto restore
for %%F in ("dist\Silage-*-Setup.exe") do move "%%F" "%BACKUP%\" >> "%LOG%" 2>&1 || goto restore
echo Backup: %BACKUP% >> "%LOG%"

echo [3/6] Assembling dist\Silage...
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

echo [4/6] Checking the packaged app finds its bundled mpv...
set "JAVA_TOOL_OPTIONS="
set "JAVA_HOME="
"dist\Silage\silage.exe" --check >> "%LOG%" 2>&1
if errorlevel 1 ( echo silage.exe --check failed - see package-windows.log & goto restore )
rem --version prints "Silage <version>"; the installer is named and stamped with it.
for /f "usebackq tokens=1,2" %%A in (`"dist\Silage\silage.exe" --version`) do if "%%A"=="Silage" set "VERSION=%%B"
if not defined VERSION ( echo silage.exe --version failed - see package-windows.log & echo No version from silage.exe --version >> "%LOG%" & goto restore )
echo Version: %VERSION% >> "%LOG%"

echo [5/6] Creating dist\Silage-%VERSION%-Setup.exe...
set "NEWSETUP=1"
"%ISCC%" "/DVersion=%VERSION%" "/DAppDir=%~dp0dist\Silage" "/O%~dp0dist" "%~dp0packaging\windows\silage.iss" >> "%LOG%" 2>&1
if errorlevel 1 ( echo Building the installer failed - see package-windows.log & goto restore )
rem Version-free copy, so releases/latest/download/Silage-Setup.exe always gets the newest.
copy /y /b "dist\Silage-%VERSION%-Setup.exe" "dist\Silage-Setup.exe" >> "%LOG%" 2>&1 || goto restore

echo [6/6] Preserving local settings...
rem Restore personal data only AFTER building the clean installer.
if exist "%BACKUP%\Silage\userdata" (
  robocopy "%BACKUP%\Silage\userdata" "dist\Silage\userdata" /E /NFL /NDL /NJH /NJS >> "%LOG%" 2>&1
  if errorlevel 8 goto restore
)

echo PACKAGE OK >> "%LOG%"
echo.
echo Done. Run dist\Silage-%VERSION%-Setup.exe to install
echo Previous copy saved in %BACKUP%
pause
exit /b 0

:restore
echo Restoring the previous dist copy... >> "%LOG%"
rem Remove new installers if a later step failed, including on the first package run.
if defined NEWSETUP if exist "dist\Silage-Setup.exe" del /q "dist\Silage-Setup.exe" >> "%LOG%" 2>&1
if defined NEWSETUP for %%F in ("dist\Silage-*-Setup.exe") do del /q "%%F" >> "%LOG%" 2>&1
if exist "%BACKUP%\Silage" (
  if exist "dist\Silage" rmdir /s /q "dist\Silage"
  move "%BACKUP%\Silage" "dist\Silage" >> "%LOG%" 2>&1
)
for %%F in ("%BACKUP%\Silage-*Setup.exe") do move "%%F" "dist\" >> "%LOG%" 2>&1
echo Packaging failed; your previous dist copy was put back. See package-windows.log
:end_fail
echo PACKAGE FAILED >> "%LOG%"
pause
exit /b 1
