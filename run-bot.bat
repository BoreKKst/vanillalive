@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist .env (
  echo File .env not found. Copy .env.example to .env and fill it in.
  pause
  exit /b 1
)
set JAR=VanilaLiveBot.jar
if not exist "%JAR%" set JAR=bot\target\VanilaLiveBot.jar
if not exist "%JAR%" (
  echo VanilaLiveBot.jar not found. Run build.bat first.
  pause
  exit /b 1
)
java -Dfile.encoding=UTF-8 -jar "%JAR%"
pause
