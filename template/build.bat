@echo off
rem Builds this mod into build\<id>.jar against your installed game and mod loader.
rem   build.bat            build hello.jar
rem   build.bat install    build and copy it into <game>\mods\
rem Set AOC_GAME if the game is not in Steam's default folder. Needs a JDK 17 or newer.
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "NAME=hello"
if "%AOC_GAME%"=="" set "AOC_GAME=C:\Program Files (x86)\Steam\steamapps\common\Age of Clicks"
set "GAME_JAR="
for %%j in ("%AOC_GAME%\app\desktop-*.jar") do set "GAME_JAR=%%~fj"
if "%GAME_JAR%"=="" (echo No game in !AOC_GAME!; set AOC_GAME to the folder with "Age of Clicks.exe" & exit /b 1)
set "API_JAR=%AOC_GAME%\app\aoc-loader.jar"
if not exist "%API_JAR%" (echo No mod loader in !AOC_GAME!; run the mod loader setup first & exit /b 1)
if exist build rmdir /s /q build
mkdir build\classes
rem javac's argument file needs quoted paths with forward slashes.
(for /f "delims=" %%f in ('dir /s /b src\*.java') do @set "F=%%f" & echo "!F:\=/!") > build\sources.txt
javac --release 17 -encoding UTF-8 -cp "%GAME_JAR%;%API_JAR%" -d build\classes @build\sources.txt || exit /b 1
xcopy /e /q /y resources build\classes\ >nul
jar --create --file build\%NAME%.jar -C build\classes . || exit /b 1
echo Built build\%NAME%.jar
if /i "%~1"=="install" (
  if not exist "!AOC_GAME!\mods" mkdir "!AOC_GAME!\mods"
  copy /y build\%NAME%.jar "!AOC_GAME!\mods\" >nul
  echo Installed into !AOC_GAME!\mods, restart the game
)
