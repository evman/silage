@echo off
rem Build with the portable JDK in toolchains\jdk-*; no Python or global Java needed.
rem Usage: build-windows.cmd <gradle tasks/options>, e.g. build-windows.cmd :app:run
setlocal
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "JDK="
for /d %%D in ("%ROOT%\toolchains\jdk-*") do if not defined JDK set "JDK=%%~fD"
if not defined JDK (
  echo No portable JDK found under "%ROOT%\toolchains\jdk-*".
  exit /b 1
)
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%ROOT%\native;%PATH%"
set "JAVA_TOOL_OPTIONS=-Djna.library.path=%ROOT%\native"
cd /d "%ROOT%"
"%JDK%\bin\java.exe" -classpath "%ROOT%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
