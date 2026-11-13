@echo off
rem Inside Setup.exe: unpack the bundled package to a temp folder and open the setup window.
set "D=%TEMP%\AocModLoaderSetup"
if exist "%D%" rmdir /s /q "%D%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%~dp0payload.zip' -DestinationPath '%D%' -Force"
for /d %%i in ("%D%\AgeOfClicks-ModAPI-*") do powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%%i\installer\Setup.ps1"
