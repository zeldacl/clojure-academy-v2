@echo off
setlocal
set "ROOT=%~dp0.."
if "%~1"=="" (set "TARGET=forge-1.20.1") else (set "TARGET=%~1" & shift)
if "%MC_JAVA_HOME_17%"=="" if not "%JAVA_HOME%"=="" set "MC_JAVA_HOME_17=%JAVA_HOME%"
if "%MC_JAVA_HOME_17%"=="" (
  echo Set MC_JAVA_HOME_17 or JAVA_HOME to Java 17 or newer. 1>&2
  exit /b 2
)
if not exist "%MC_JAVA_HOME_17%\bin\java.exe" (
  echo Java executable not found: %MC_JAVA_HOME_17%\bin\java.exe 1>&2
  exit /b 2
)
call "%ROOT%\gradlew.bat" :tools:target-launcher:installDist --no-daemon
if errorlevel 1 exit /b %ERRORLEVEL%
call "%ROOT%\tools\target-launcher\build\install\target-launcher\bin\target-launcher.bat" %TARGET% %*
exit /b %ERRORLEVEL%
