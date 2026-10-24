@echo off
rem Starts the Claude Code helper on http://127.0.0.1:8787.
rem Options pass through, for example:  start-helper.bat --token my-secret --model claude-haiku-5-5
rem Press Ctrl+C in this window to stop it.
setlocal
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -version >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install Java 17 or newer, for example from https://adoptium.net
  pause
  exit /b 1
)
"%JAVA%" -jar "%~dp0claude-code-helper.jar" %*
if errorlevel 1 (
  echo.
  echo The helper stopped with an error. If it says UnsupportedClassVersionError, your Java is older than 17:
  echo install Java 17 or newer and set JAVA_HOME to it. HOW-IT-WORKS.txt has a troubleshooting list.
  pause
)
endlocal
