# Lucense's update helper (Windows): the step that asks for administrator
# rights. Windows PowerShell 5.1 or later. Runs as the user.
#
# Never run from a file: the app passes this text as an encoded command, and
# the code for the administrator, apply-update.ps1 in swap mode, as an encoded
# command in the environment variable LUCENSE_UPDATE_ELEVATED (both built in
# UpdateApplier). A copy in the user's folder could be replaced by any program
# running as the user while the UAC prompt is open, and the administrator
# would then run that program's code; nothing here is read from a file.
#
# This starts the elevated PowerShell (Windows shows its UAC prompt), and while
# it runs passes its word on to the app: the elevated part writes only into
# ROOT\.lucense-update, inside the install, which only administrators can
# change and the user can read; this copies 'ready' into the user's
# apply.state, and once it is done the result into RESULT, as the user. Then
# it starts Lucense, as the user, never as administrator.
#
#   apply-update-elevate.ps1 ROOT STAGING VERSION RESULT LAUNCHER TOKEN
#
# No here-strings or block comments: UpdateApplier.compact drops every line
# that starts with '#'.
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Root,
    [Parameter(Mandatory = $true, Position = 1)][string]$Staging,
    [Parameter(Mandatory = $true, Position = 2)][string]$Version,
    [Parameter(Mandatory = $true, Position = 3)][string]$Result,
    [Parameter(Mandatory = $true, Position = 4)][string]$Launcher,
    [Parameter(Mandatory = $true, Position = 5)][string]$Token
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$Private = Join-Path $Root '.lucense-update'
$StateFile = Join-Path $Staging 'apply.state'
$LogFile = Join-Path $Staging 'apply-elevate.log'
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Write-Log([string]$Text) {
    try { [System.IO.File]::AppendAllText($LogFile, ((Get-Date).ToString('s') + ' ' + $Text + "`r`n"), $Utf8) } catch { }
}

# Whole or not at all: written beside the file and renamed over it.
function Write-UserFile([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path + '.tmp', $Text, $Utf8)
    Move-Item -LiteralPath ($Path + '.tmp') -Destination $Path -Force
}

# What the elevated part left in its folder, or '' if nothing.
function Read-Private([string]$Name) {
    $path = Join-Path $Private $Name
    if (Test-Path -LiteralPath $path -PathType Leaf) { return [System.IO.File]::ReadAllText($path, $Utf8).Trim() }
    return ''
}

function Start-App {
    try {
        Start-Process -FilePath (Join-Path $Root ($Launcher.Replace('/', '\'))) -WorkingDirectory $Root
    } catch {
        Write-Log ('could not start Lucense: ' + $_.Exception.Message)
    }
}

# Only base64 is ever put on the elevated command line: nothing in it can end
# the argument or start another one.
$encoded = [string]$env:LUCENSE_UPDATE_ELEVATED
if ($encoded -notmatch '^[A-Za-z0-9+/]+={0,2}$' -or $Token -notmatch '^[0-9a-f]{32}$' -or $Version -notmatch '^[0-9]+(\.[0-9]+)*$') {
    Write-Log 'refused: no elevated command'
    Write-UserFile $StateFile 'failed'
    exit 2
}
# The line UpdateApplier.elevatedArgumentLine describes.
$argumentLine = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand ' + $encoded

if (Test-Path -LiteralPath $StateFile) { Remove-Item -LiteralPath $StateFile -Force }
try {
    $elevated = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList $argumentLine -Verb RunAs -WindowStyle Hidden -PassThru
} catch {
    # The UAC prompt was dismissed, or this account may not elevate.
    Write-Log ('not elevated: ' + $_.Exception.Message)
    Write-UserFile $StateFile 'cancelled'
    exit 0
}

# 'ready TOKEN' is this run's word; anything else there is from an earlier one.
$ready = $false
$done = $false
while (-not $done) {
    $done = $elevated.WaitForExit(250)
    if (-not $ready -and (Read-Private 'apply.state') -eq ('ready ' + $Token)) {
        $ready = $true
        Write-UserFile $StateFile 'ready'
    }
}
$swapLog = Read-Private 'apply-swap.log'
if ($swapLog -ne '') { Write-Log $swapLog }
if (-not $ready) {
    # It stopped on its own checks before starting: the app is still running and says so.
    Write-UserFile $StateFile 'failed'
    exit 0
}
$answer = Read-Private 'result.json'
if ($answer -eq '') {
    $answer = '{"ok":false,"version":"' + $Version + '","error":"The update helper stopped unexpectedly (status ' + $elevated.ExitCode + ')."}'
}
try {
    [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($Result))
    Write-UserFile $Result $answer
} catch {
    Write-Log ('could not write the result: ' + $_.Exception.Message)
}
Start-App
exit 0
