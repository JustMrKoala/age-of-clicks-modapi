# Installs the Age of Clicks Mod Loader and the bundled mods.
# Usage: Setup.exe / Setup.bat (window), Install.bat, or:
#   powershell -ExecutionPolicy Bypass -File install.ps1 [-GameDir "<path>"] [-NoMultiplayer] [-NoDiplomacy]
#                                                       [-CentralServer host:port] [-Quiet]
# -Quiet never asks questions (used by the Setup window): it stops with an error instead.
param([string]$GameDir = "", [switch]$NoMultiplayer, [switch]$NoDiplomacy, [string]$CentralServer = "", [switch]$Quiet)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$files = Join-Path (Split-Path -Parent $here) "files"
$agentLine = 'java-options=-javaagent:$APPDIR/aoc-loader.jar'

. (Join-Path $here "find-game.ps1")

# Sets key=value in mods\aoc-loader\config.properties, keeping every other line.
function Set-LoaderSetting([string]$mods, [string]$key, [string]$value) {
    $dir = Join-Path $mods "aoc-loader"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $file = Join-Path $dir "config.properties"
    $lines = @()
    if (Test-Path $file) { $lines = @(Get-Content $file | Where-Object { $_ -notmatch ('^' + [regex]::Escape($key) + '=') }) }
    $lines += "$key=$value"
    [IO.File]::WriteAllLines($file, [string[]]$lines)
}

Write-Host ""
Write-Host "Age of Clicks Mod Loader - installer" -ForegroundColor Yellow
Write-Host ""

if (-not (Test-GameDir $GameDir)) { $GameDir = Find-GameDir }
while (-not (Test-GameDir $GameDir)) {
    if ($Quiet) { throw "Age of Clicks was not found. Choose the game folder (the one with 'Age of Clicks.exe')." }
    $GameDir = Read-Host "Could not find Age of Clicks. Paste the game folder (Steam: right-click the game > Manage > Browse local files)"
    $GameDir = $GameDir.Trim('"', ' ')
}
Write-Host "Game folder: $GameDir"

while (Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($GameDir) }) {
    if ($Quiet) { throw "Age of Clicks is running. Close the game first." }
    Read-Host "Age of Clicks is running. Close it, then press Enter" | Out-Null
}

$app = Join-Path $GameDir "app"
$mods = Join-Path $GameDir "mods"
New-Item -ItemType Directory -Force -Path $mods | Out-Null

# The loader patches specific game classes; a different game build may need a newer loader.
$builtFor = ""
$info = Join-Path $files "build-info.txt"
if (Test-Path $info) { $builtFor = ((Get-Content $info | Where-Object { $_ -like "game_jar=*" }) -replace '^game_jar=', '') }
$gameJar = (Get-ChildItem $app -Filter "desktop-*.jar" | Select-Object -First 1).Name
if ($builtFor -and $gameJar -and $gameJar -ne $builtFor) {
    Write-Host "  Note: this build was made for $builtFor, this game has $gameJar." -ForegroundColor DarkYellow
    Write-Host "  It will probably work; if the game misbehaves, uninstall and ask for an updated build." -ForegroundColor DarkYellow
}

Copy-Item (Join-Path $files "app\aoc-loader.jar") (Join-Path $app "aoc-loader.jar") -Force
Write-Host "  installed app\aoc-loader.jar"
foreach ($jar in Get-ChildItem (Join-Path $files "mods") -Filter *.jar) {
    if ($NoDiplomacy -and $jar.Name -eq "diplomacy.jar") {
        Remove-Item (Join-Path $mods $jar.Name) -ErrorAction SilentlyContinue
        Write-Host "  skipped mods\$($jar.Name)"
        continue
    }
    Copy-Item $jar.FullName (Join-Path $mods $jar.Name) -Force
    Write-Host "  installed mods\$($jar.Name)"
}

if ($NoMultiplayer) {
    Set-LoaderSetting $mods "experimental_multiplayer" "false"
    Write-Host "  multiplayer menu off"
} else {
    Set-LoaderSetting $mods "experimental_multiplayer" "true"
    Write-Host "  multiplayer enabled (main menu > MULTIPLAYER)"
}
if ($CentralServer) {
    Set-LoaderSetting $mods "central_server" $CentralServer.Trim()
    Write-Host "  central server: $($CentralServer.Trim())"
}

$cfg = Join-Path $app "Age of Clicks.cfg"
$text = [IO.File]::ReadAllText($cfg)
if ($text -notmatch 'app\.mainclass=ru\.ius\.david\.cgs\.DesktopLauncher' -or $text -match 'cgs-modloader') {
    Write-Host ""
    Write-Host "  Note: another mod loader has changed the game's launch settings." -ForegroundColor DarkYellow
    Write-Host "  Both may load, but they were not made to work together. If the game misbehaves," -ForegroundColor DarkYellow
    Write-Host "  verify the game files in Steam and run this installer again." -ForegroundColor DarkYellow
}
if ($text.Contains($agentLine)) {
    Write-Host "  launch settings already set"
} else {
    $vanilla = "$cfg.vanilla"
    if (-not (Test-Path $vanilla)) { Copy-Item $cfg $vanilla }
    $nl = "`n"
    if ($text.Contains("`r`n")) { $nl = "`r`n" }
    # '$' starts a substitution in -replace, so the line's own '$' is doubled.
    $text = $text -replace '(\[JavaOptions\]\r?\n)', ('$1' + $agentLine.Replace('$', '$$') + $nl)
    [IO.File]::WriteAllText($cfg, $text)
    Write-Host "  launch settings updated (original kept as app\Age of Clicks.cfg.vanilla)"
}

Write-Host ""
Write-Host "Done. Start Age of Clicks; mod settings are in the main menu under MODS > CODE MODS." -ForegroundColor Green
Write-Host "After a Steam update, run the installer again."
Write-Host ""
