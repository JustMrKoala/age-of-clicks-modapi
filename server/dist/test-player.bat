@echo off
rem A pretend player for testing without a friend: joins a host or server at any address,
rem marks itself ready and stays up to 30 minutes as your second player (close the window to
rem leave), then prints PASS or FAIL. Needs Java 17+.
rem Usage: test-player.bat [address]   (asks when no address is given)
cd /d "%~dp0"
set GAME_JAR=
for %%f in (desktop-*.jar) do set GAME_JAR=%%f
if not defined GAME_JAR for %%f in (..\app\desktop-*.jar) do set GAME_JAR=%%f
set ADDRESS=%1
if "%ADDRESS%"=="" set /p ADDRESS=Address of the host or server (127.0.0.1 = this PC):
java -cp "aoc-server.jar;%GAME_JAR%" aoc.server.TestPlayer %ADDRESS% TestPlayer 1800
pause
