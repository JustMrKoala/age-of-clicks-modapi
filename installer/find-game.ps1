# Shared by install.ps1 and uninstall.ps1: locate the Age of Clicks install folder.

function Test-GameDir([string]$dir) {
    return $dir -and (Test-Path (Join-Path $dir "Age of Clicks.exe")) -and (Test-Path (Join-Path $dir "app"))
}

# Checks the Steam folder and every Steam library listed in libraryfolders.vdf.
function Find-GameDir {
    $candidates = @()
    try {
        $steam = (Get-ItemProperty -Path "HKCU:\Software\Valve\Steam" -ErrorAction Stop).SteamPath
        if ($steam) {
            $candidates += (Join-Path $steam "steamapps\common\Age of Clicks")
            $vdf = Join-Path $steam "steamapps\libraryfolders.vdf"
            if (Test-Path $vdf) {
                foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) {
                    $lib = $m.Groups[1].Value -replace '\\\\', '\'
                    $candidates += (Join-Path $lib "steamapps\common\Age of Clicks")
                }
            }
        }
    } catch { }
    $candidates += "C:\Program Files (x86)\Steam\steamapps\common\Age of Clicks"
    foreach ($c in $candidates) { if (Test-GameDir $c) { return $c } }
    return $null
}
