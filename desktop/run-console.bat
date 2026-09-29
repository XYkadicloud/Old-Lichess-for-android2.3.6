@echo off
rem ===========================================================================
rem  lichess desktop - console launcher (for troubleshooting)
rem
rem  Same as run.bat but keeps a console window so startup errors are visible.
rem ===========================================================================
setlocal enabledelayedexpansion
set "HERE=%~dp0"

set "JAVA_EXE=%HERE%..\toolchain\jdk8\bin\java.exe"
if not exist "%JAVA_EXE%" if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java.exe"

set "JAR="
for %%f in ("%HERE%dist\LichessOldDesktop-*.jar") do set "JAR=%%~ff"

if "%JAR%"=="" (
  echo   [ERROR] JAR not found in: %HERE%dist\
  echo   Build it first:  bash desktop/build.sh
  pause
  exit /b 1
)

echo   jar : %JAR%
echo   java: %JAVA_EXE%
echo.
"%JAVA_EXE%" -Xmx1g -jar "%JAR%"

echo.
echo   (exited with code %ERRORLEVEL%)
pause
