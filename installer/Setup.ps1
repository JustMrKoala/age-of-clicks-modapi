# Age of Clicks Mod Loader setup window. Started by Setup.exe or Setup.bat.
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$files = Join-Path (Split-Path -Parent $here) "files"
. (Join-Path $here "find-game.ps1")

$build = @{ version = "?"; game_jar = ""; central_server = "" }
$infoFile = Join-Path $files "build-info.txt"
if (Test-Path $infoFile) {
    foreach ($line in Get-Content $infoFile) { if ($line -match '^([a-z_]+)=(.*)$') { $build[$matches[1]] = $matches[2] } }
}

$form = New-Object System.Windows.Forms.Form
$form.Text = "Age of Clicks Mod Loader $($build.version) - Setup"
$form.Size = New-Object System.Drawing.Size(640, 560)
$form.StartPosition = "CenterScreen"
$form.FormBorderStyle = "FixedDialog"
$form.MaximizeBox = $false
$font = New-Object System.Drawing.Font("Segoe UI", 10)
$form.Font = $font

function Add-Control($control, $x, $y, $w, $h) {
    $control.Location = New-Object System.Drawing.Point($x, $y)
    $control.Size = New-Object System.Drawing.Size($w, $h)
    $form.Controls.Add($control)
    return $control
}

$title = Add-Control (New-Object System.Windows.Forms.Label) 16 12 600 28
$title.Text = "Age of Clicks Mod Loader"
$title.Font = New-Object System.Drawing.Font("Segoe UI", 14, [System.Drawing.FontStyle]::Bold)
$sub = Add-Control (New-Object System.Windows.Forms.Label) 16 42 600 22
$sub.Text = "Code mods, AI Diplomacy and online multiplayer for Age of Clicks (unofficial)."

$lblDir = Add-Control (New-Object System.Windows.Forms.Label) 16 76 600 20
$lblDir.Text = "Game folder (the one with 'Age of Clicks.exe'):"
$txtDir = Add-Control (New-Object System.Windows.Forms.TextBox) 16 98 490 26
$btnBrowse = Add-Control (New-Object System.Windows.Forms.Button) 514 96 94 30
$btnBrowse.Text = "Browse..."
$lblFound = Add-Control (New-Object System.Windows.Forms.Label) 16 128 600 20

$chkDiplo = Add-Control (New-Object System.Windows.Forms.CheckBox) 16 158 600 24
$chkDiplo.Text = "AI Diplomacy mod (talk to country leaders; needs a free Groq API key)"
$chkDiplo.Checked = $true
$chkMp = Add-Control (New-Object System.Windows.Forms.CheckBox) 16 186 600 24
$chkMp.Text = "Online multiplayer (MULTIPLAYER in the main menu, no Steam networking)"
$chkMp.Checked = $true
$lblServer = Add-Control (New-Object System.Windows.Forms.Label) 36 214 160 22
$lblServer.Text = "Central server:"
$txtServer = Add-Control (New-Object System.Windows.Forms.TextBox) 160 212 250 26
$txtServer.Text = $build.central_server

$btnInstall = Add-Control (New-Object System.Windows.Forms.Button) 16 252 150 36
$btnInstall.Text = "Install / Update"
$btnUninstall = Add-Control (New-Object System.Windows.Forms.Button) 176 252 120 36
$btnUninstall.Text = "Uninstall"
$btnClose = Add-Control (New-Object System.Windows.Forms.Button) 488 252 120 36
$btnClose.Text = "Close"

$log = Add-Control (New-Object System.Windows.Forms.TextBox) 16 300 592 200
$log.Multiline = $true
$log.ReadOnly = $true
$log.ScrollBars = "Vertical"
$log.Font = New-Object System.Drawing.Font("Consolas", 9)

function Update-Found {
    $dir = $txtDir.Text.Trim('"', ' ')
    if (Test-GameDir $dir) {
        $installed = Test-Path (Join-Path $dir "app\aoc-loader.jar")
        $lblFound.ForeColor = [System.Drawing.Color]::DarkGreen
        $lblFound.Text = "Age of Clicks found." + $(if ($installed) { " The mod loader is installed; Install updates it." } else { "" })
        $btnInstall.Enabled = $true
        $btnUninstall.Enabled = $installed
    } else {
        $lblFound.ForeColor = [System.Drawing.Color]::DarkRed
        $lblFound.Text = "No Age of Clicks here. Use Browse (Steam: right-click the game > Manage > Browse local files)."
        $btnInstall.Enabled = $false
        $btnUninstall.Enabled = $false
    }
}

function Invoke-Script([string]$script, [string[]]$arguments) {
    $log.Clear()
    $form.Cursor = [System.Windows.Forms.Cursors]::WaitCursor
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "powershell.exe"
    $quoted = $arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $here $script)`" " + ($quoted -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    $form.Cursor = [System.Windows.Forms.Cursors]::Default
    $log.Text = ($out + $err).Trim() -replace "`r?`n", "`r`n"
    Update-Found
    return $p.ExitCode -eq 0
}

$btnBrowse.Add_Click({
    $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
    $dlg.Description = "Choose the Age of Clicks game folder"
    if ($dlg.ShowDialog() -eq "OK") { $txtDir.Text = $dlg.SelectedPath; Update-Found }
})
$txtDir.Add_TextChanged({ Update-Found })
$chkMp.Add_CheckedChanged({ $txtServer.Enabled = $chkMp.Checked })
$btnClose.Add_Click({ $form.Close() })

$btnInstall.Add_Click({
    $argList = @("-GameDir", $txtDir.Text.Trim('"', ' '), "-Quiet")
    if (-not $chkDiplo.Checked) { $argList += "-NoDiplomacy" }
    if (-not $chkMp.Checked) { $argList += "-NoMultiplayer" }
    elseif ($txtServer.Text.Trim()) { $argList += @("-CentralServer", $txtServer.Text.Trim()) }
    if (Invoke-Script "install.ps1" $argList) {
        [System.Windows.Forms.MessageBox]::Show("Installed. Start Age of Clicks through Steam as usual.`r`nMod settings: main menu > MODS > CODE MODS.", "Done") | Out-Null
    } else {
        [System.Windows.Forms.MessageBox]::Show("Installation failed - see the log.", "Setup") | Out-Null
    }
})

$btnUninstall.Add_Click({
    $remove = [System.Windows.Forms.MessageBox]::Show("Also delete the code mods (your mod settings are kept)?", "Uninstall", "YesNoCancel")
    if ($remove -eq "Cancel") { return }
    $argList = @("-GameDir", $txtDir.Text.Trim('"', ' '), "-Quiet")
    if ($remove -eq "Yes") { $argList += "-RemoveMods" }
    Invoke-Script "uninstall.ps1" $argList | Out-Null
})

$found = Find-GameDir
if ($found) { $txtDir.Text = $found }
Update-Found
[void]$form.ShowDialog()
