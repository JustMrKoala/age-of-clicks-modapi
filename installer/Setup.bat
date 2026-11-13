@echo off
rem Opens the Age of Clicks Mod Loader setup window.
start "" powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0installer\Setup.ps1"
