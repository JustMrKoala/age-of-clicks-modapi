# Builds Setup.exe with Windows' built-in IExpress: payload.zip + run-setup.cmd in one file.
# Usage: powershell -File make-exe.ps1 -Zip <package.zip> -Out <Setup.exe>
param([string]$Zip, [string]$Out)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $env:TEMP "aoc-iexpress"
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null
Copy-Item $Zip (Join-Path $work "payload.zip")
Copy-Item (Join-Path $here "run-setup.cmd") (Join-Path $work "run-setup.cmd")
$Out = [IO.Path]::GetFullPath($Out)
# IExpress mishandles output paths with spaces or brackets, so it writes into the work folder.
$built = Join-Path $work "Setup.exe"
$sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=1
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=%InstallPrompt%
DisplayLicense=%DisplayLicense%
FinishMessage=%FinishMessage%
TargetName=%TargetName%
FriendlyName=%FriendlyName%
AppLaunched=%AppLaunched%
PostInstallCmd=%PostInstallCmd%
AdminQuietInstCmd=%AdminQuietInstCmd%
UserQuietInstCmd=%UserQuietInstCmd%
SourceFiles=SourceFiles
[Strings]
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$built
FriendlyName=Age of Clicks Mod Loader Setup
AppLaunched=cmd /c run-setup.cmd
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
FILE0="payload.zip"
FILE1="run-setup.cmd"
[SourceFiles]
SourceFiles0=$work\
[SourceFiles0]
%FILE0%=
%FILE1%=
"@
$sedFile = Join-Path $work "setup.sed"
# IExpress reads the SED like an old INI file: Windows line endings, plain ANSI.
[IO.File]::WriteAllText($sedFile, ($sed -replace "`r?`n", "`r`n"), [Text.Encoding]::Default)
$p = Start-Process -FilePath "$env:WINDIR\System32\iexpress.exe" -ArgumentList "/N", "/Q", "/M", $sedFile -Wait -PassThru
if (-not (Test-Path $built)) { throw "IExpress did not create Setup.exe (exit $($p.ExitCode))" }
Move-Item $built $Out -Force
Write-Host "Built $Out"
