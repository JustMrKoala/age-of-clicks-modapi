@echo off
rem Runs the Age of Clicks dedicated server on Windows (needs Java 17+).
rem Uses desktop-*.jar from this folder, or from ..\app\ when this folder sits inside the game folder.
cd /d "%~dp0"
set GAME_JAR=
for %%f in (desktop-*.jar) do set GAME_JAR=%%f
if not defined GAME_JAR for %%f in (..\app\desktop-*.jar) do set GAME_JAR=%%f
if not defined GAME_JAR (
  echo Copy desktop-*.jar from your Age of Clicks app folder next to this file first.
  pause
  exit /b 1
)
java -Xmx2g -cp "aoc-server.jar;%GAME_JAR%" aoc.server.DedicatedServer server.properties
pause
