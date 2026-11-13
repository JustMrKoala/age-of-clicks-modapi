# Removes the Age of Clicks Mod Loader. Mod jars and their settings stay unless you choose to remove them.
# -Quiet never asks (keeps the mod jars unless -RemoveMods); used by the Setup window and tests.
param([string]$GameDir = "", [switch]$Quiet, [switch]$RemoveMods)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here "find-game.ps1")

if (-not (Test-GameDir $GameDir)) { $GameDir = Find-GameDir }
while (-not (Test-GameDir $GameDir)) {
    if ($Quiet) { throw "Age of Clicks was not found in that folder." }
    $GameDir = (Read-Host "Paste the Age of Clicks game folder").Trim('"', ' ')
}
while (Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($GameDir) }) {
    if ($Quiet) { throw "Age of Clicks is running. Close the game first." }
    Read-Host "Age of Clicks is running. Close it, then press Enter" | Out-Null
}

$cfg = Join-Path $GameDir "app\Age of Clicks.cfg"
$text = [IO.File]::ReadAllText($cfg)
$cleaned = $text -replace 'java-options=-javaagent:\$APPDIR/aoc-loader\.jar\r?\n', ''
if ($cleaned -ne $text) {
    [IO.File]::WriteAllText($cfg, $cleaned)
    Write-Host "  launch settings restored"
}
Remove-Item (Join-Path $GameDir "app\aoc-loader.jar") -ErrorAction SilentlyContinue
Write-Host "  removed app\aoc-loader.jar"

$answer = "n"
if ($RemoveMods) { $answer = "y" }
if (-not $Quiet -and -not $RemoveMods) { $answer = Read-Host "Also delete the code mods in the mods folder (their settings are kept)? [y/N]" }
if ($answer -match '^[yY]') {
    Get-ChildItem (Join-Path $GameDir "mods") -Filter *.jar | Remove-Item
    Write-Host "  removed mod jars"
}
Write-Host ""
Write-Host "Uninstalled. The game starts without mods now." -ForegroundColor Green
