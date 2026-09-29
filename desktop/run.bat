@echo off
rem ===========================================================================
rem  lichess desktop launcher  (double-click to run)
rem
rem  Java priority:
rem    1) bundled toolchain JDK8  (..\toolchain\jdk8)  -- same JVM used to build
rem    2) %JAVA_HOME%\bin
rem    3) javaw.exe from PATH
rem
rem  Logs go to:  <this folder>\data\logs\log.txt
rem  Settings:    <this folder>\data\settings.properties
rem ===========================================================================
setlocal enabledelayedexpansion
set "HERE=%~dp0"

set "JAVA_EXE=%HERE%..\toolchain\jdk8\bin\javaw.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=%HERE%..\toolchain\jdk8\bin\java.exe"
if not exist "%JAVA_EXE%" if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=javaw.exe"

set "JAR="
for %%f in ("%HERE%dist\LichessOldDesktop-*.jar") do set "JAR=%%~ff"

if "%JAR%"=="" (
  echo.
  echo   [ERROR] JAR not found in: %HERE%dist\
  echo   Build it first:  bash desktop/build.sh
  echo.
  pause
  exit /b 1
)

echo   lichess desktop
echo   jar : %JAR%
echo   java: %JAVA_EXE%
echo.

start "" "%JAVA_EXE%" -Xmx1g -jar "%JAR%"
exit /b 0
