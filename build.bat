@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
where mvn >nul 2>nul
if errorlevel 1 (
  echo Maven not found in PATH. Install Maven 3.9+ and JDK 21.
  exit /b 1
)
call mvn -B clean package
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)
if not exist dist mkdir dist
copy /y bot\target\VanilaLiveBot.jar dist\ >nul
copy /y minecraft-plugin\target\VanilaLivePlugin.jar dist\ >nul
copy /y run-bot.bat dist\ >nul
copy /y .env.example dist\ >nul
echo.
echo BUILD OK. Files are in the "dist" folder.
