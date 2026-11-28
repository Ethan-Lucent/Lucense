# Lucense's update helper (Windows). Windows PowerShell 5.1 or later.
#
# The running app can't replace its own jars (Windows keeps them locked), so it
# stages the new files, writes this script and a plan next to them, starts the
# script and exits. This waits for the app to be gone, backs up every file it
# is about to replace or delete, puts the new files in place, checks every one
# against the SHA-256 in the plan and starts Lucense again. If anything at all
# goes wrong it puts every old file back from the backup, so the install is
# either all old or all new, never half of each.
#
#   apply-update.ps1 MODE PID ROOT STAGING VERSION PLAN RESULT LAUNCHER
#
#   MODE      run           do the swap as this user, then start Lucense
#             run-elevated  do the swap in an administrator copy of this script
#                           (the UAC prompt), then start Lucense as this user
#             swap          (elevated) the swap only
#   PLAN      one line per file: path<TAB>sha256<TAB>exec(0|1); per old jar: stale<TAB>path
#   LAUNCHER  the launcher, relative to ROOT (Lucense.exe)
#
# File names are only read from the plan and passed as -LiteralPath values,
# never pasted into a command line, so a name can't turn into a command.
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Mode,
    [Parameter(Mandatory = $true, Position = 1)][string]$AppPid,
    [Parameter(Mandatory = $true, Position = 2)][string]$Root,
    [Parameter(Mandatory = $true, Position = 3)][string]$Staging,
    [Parameter(Mandatory = $true, Position = 4)][string]$Version,
    [Parameter(Mandatory = $true, Position = 5)][string]$Plan,
    [Parameter(Mandatory = $true, Position = 6)][string]$Result,
    [Parameter(Mandatory = $true, Position = 7)][string]$Launcher
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$Files = Join-Path $Staging 'files'
$Backup = Join-Path $Staging 'backup'
$StateFile = Join-Path $Staging 'apply.state'
$LogFile = Join-Path $Staging ('apply-' + $Mode + '.log')
$WaitSeconds = 60
if ($env:LUCENSE_UPDATE_WAIT_SECONDS -match '^[0-9]+$') { $WaitSeconds = [int]$env:LUCENSE_UPDATE_WAIT_SECONDS }
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Write-Log([string]$Text) {
    try { [System.IO.File]::AppendAllText($LogFile, ((Get-Date).ToString('s') + ' ' + $Text + "`r`n"), $Utf8) } catch { }
}

function Stop-Refused([string]$Why) {
    Write-Log ('refused: ' + $Why)
    exit 2
}

function Test-Absolute([string]$Path) {
    if ($Path -notmatch '^[A-Za-z]:\\') { return $false }
    if ($Path.Contains('"')) { return $false }
    foreach ($part in $Path.Split('\')) { if ($part -eq '..' -or $part -eq '.') { return $false } }
    return $true
}

# A path from the plan: relative, forward slashes, no "." or ".." part, no
# backslash, colon or control character. It can only name something inside ROOT.
function Test-SafeRelative([string]$Path) {
    if ([string]::IsNullOrEmpty($Path)) { return $false }
    if ($Path.StartsWith('/') -or $Path.EndsWith('/') -or $Path.Contains('//')) { return $false }
    if ($Path -match '[\\:*?"<>|]' -or $Path -match '[\x00-\x1f\x7f]') { return $false }
    foreach ($part in $Path.Split('/')) {
        if ($part -eq '..' -or $part -eq '.' -or $part.EndsWith('.') -or $part.EndsWith(' ')) { return $false }
    }
    return $true
}

function Get-Native([string]$Relative) {
    return (Join-Path $Root ($Relative.Replace('/', '\')))
}

function Write-State([string]$State) {
    [System.IO.File]::WriteAllText($StateFile + '.tmp', $State, $Utf8)
    Move-Item -LiteralPath ($StateFile + '.tmp') -Destination $StateFile -Force
}

function ConvertTo-JsonText([string]$Text) {
    $clean = $Text -replace '[\x00-\x1f]', ' '
    return $clean.Replace('\', '\\').Replace('"', '\"')
}

function Write-Result([bool]$Ok, [string]$Message) {
    $folder = [System.IO.Path]::GetDirectoryName($Result)
    if (-not (Test-Path -LiteralPath $folder)) { [void][System.IO.Directory]::CreateDirectory($folder) }
    if ($Ok) {
        $json = '{"ok":true,"version":"' + $Version + '"}'
    } else {
        $json = '{"ok":false,"version":"' + $Version + '","error":"' + (ConvertTo-JsonText $Message) + '"}'
    }
    [System.IO.File]::WriteAllText($Result + '.tmp', $json, $Utf8)
    Move-Item -LiteralPath ($Result + '.tmp') -Destination $Result -Force
}

function Get-Sha([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# Windows may hold a file a moment after the process that had it open ends:
# a few tries, a second apart, before giving up.
function Move-Over([string]$From, [string]$To) {
    for ($try = 1; $try -le 5; $try++) {
        try {
            Move-Item -LiteralPath $From -Destination $To -Force
            return
        } catch {
            if ($try -eq 5) { throw }
            Start-Sleep -Seconds 1
        }
    }
}

function Remove-Quietly([string]$Path) {
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
}

function Wait-App {
    for ($waited = 0; $waited -lt $WaitSeconds; $waited++) {
        $process = Get-Process -Id ([int]$AppPid) -ErrorAction SilentlyContinue
        if ($null -eq $process) { return $true }
        Start-Sleep -Seconds 1
    }
    return ($null -eq (Get-Process -Id ([int]$AppPid) -ErrorAction SilentlyContinue))
}

function Read-Plan {
    $lines = New-Object System.Collections.Generic.List[object]
    foreach ($text in [System.IO.File]::ReadAllLines($Plan, $Utf8)) {
        if ($text.Length -eq 0) { continue }
        $fields = $text.Split("`t")
        if ($fields.Length -eq 2 -and $fields[0] -eq 'stale') {
            $line = [pscustomobject]@{ Stale = $true; Path = $fields[1]; Sha = ''; Exec = $false }
            if (-not $line.Path.EndsWith('.jar')) { throw ('The plan would remove something that is not an old jar: ' + $line.Path) }
        } elseif ($fields.Length -eq 3) {
            $line = [pscustomobject]@{ Stale = $false; Path = $fields[0]; Sha = $fields[1]; Exec = ($fields[2] -eq '1') }
            if ($line.Sha -notmatch '^[0-9a-f]{64}$') { throw ('The plan has a bad checksum for ' + $line.Path) }
        } else {
            throw 'The plan is damaged.'
        }
        if (-not (Test-SafeRelative $line.Path)) { throw 'The plan names an unsafe path.' }
        $lines.Add($line)
    }
    return , $lines
}

# The swap itself. Every step is recorded as it is taken, so a failure at any
# point is undone exactly: $Touched is what was changed, $BackedUp what has a
# copy in the backup, $Created the files that didn't exist before, $Folders
# the folders made.
$BackedUp = New-Object System.Collections.Generic.HashSet[string]
$Created = New-Object System.Collections.Generic.HashSet[string]
$Touched = New-Object System.Collections.Generic.List[string]
$Folders = New-Object System.Collections.Generic.List[string]

function Invoke-Swap($Lines) {
    foreach ($line in $Lines) {
        $dest = Get-Native $line.Path
        if (-not $line.Stale) {
            $source = Join-Path $Files ($line.Path.Replace('/', '\'))
            if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw ('The downloaded copy of ' + $line.Path + ' is missing.') }
        }
        if (Test-Path -LiteralPath $dest) {
            if (-not (Test-Path -LiteralPath $dest -PathType Leaf)) { throw ($line.Path + ' in the install is not a plain file.') }
            $copy = Join-Path $Backup ($line.Path.Replace('/', '\'))
            $copyFolder = [System.IO.Path]::GetDirectoryName($copy)
            [void][System.IO.Directory]::CreateDirectory($copyFolder)
            Copy-Item -LiteralPath $dest -Destination $copy -Force
            [void]$BackedUp.Add($line.Path)
        } elseif (-not $line.Stale) {
            [void]$Created.Add($line.Path)
        }
    }
    foreach ($line in $Lines) {
        $dest = Get-Native $line.Path
        if ($line.Stale) {
            $Touched.Add($line.Path)
            Remove-Quietly $dest
            continue
        }
        $folder = [System.IO.Path]::GetDirectoryName($dest)
        $missing = New-Object System.Collections.Generic.List[string]
        $probe = $folder
        while (-not (Test-Path -LiteralPath $probe)) { $missing.Add($probe); $probe = [System.IO.Path]::GetDirectoryName($probe) }
        foreach ($made in $missing) { $Folders.Add($made) }
        if ($missing.Count -gt 0) { [void][System.IO.Directory]::CreateDirectory($folder) }
        $Touched.Add($line.Path)
        $new = $dest + '.lucense-new'
        Remove-Quietly $new
        Copy-Item -LiteralPath (Join-Path $Files ($line.Path.Replace('/', '\'))) -Destination $new -Force
        Move-Over $new $dest
    }
}

function Invoke-Verify($Lines) {
    foreach ($line in $Lines) {
        $dest = Get-Native $line.Path
        if ($line.Stale) {
            if (Test-Path -LiteralPath $dest) { throw ('The old ' + $line.Path + ' is still there.') }
            continue
        }
        if (-not (Test-Path -LiteralPath $dest -PathType Leaf)) { throw ($line.Path + ' is missing after installing.') }
        if ((Get-Sha $dest) -ne $line.Sha) { throw ($line.Path + ' does not match the release after installing.') }
    }
}

# Puts back every file the swap touched, takes away the ones it made, and
# checks the result byte for byte (by hash) against the backup.
function Invoke-Rollback {
    $problems = 0
    foreach ($path in $Touched) {
        $dest = Get-Native $path
        try {
            Remove-Quietly ($dest + '.lucense-new')
            if ($BackedUp.Contains($path)) {
                $copy = Join-Path $Backup ($path.Replace('/', '\'))
                # Never got as far as changing this one: nothing to put back.
                if ((Test-Path -LiteralPath $dest -PathType Leaf) -and ((Get-Sha $copy) -eq (Get-Sha $dest))) { continue }
                Copy-Item -LiteralPath $copy -Destination ($dest + '.lucense-new') -Force
                Move-Over ($dest + '.lucense-new') $dest
            } elseif ($Created.Contains($path)) {
                Remove-Quietly $dest
            }
        } catch {
            $problems++
            Write-Log ('could not put back ' + $path + ': ' + $_.Exception.Message)
        }
    }
    # Deepest first; only empty folders are removed.
    foreach ($folder in ($Folders | Sort-Object -Descending -Unique)) {
        try {
            if ((Test-Path -LiteralPath $folder) -and @(Get-ChildItem -LiteralPath $folder -Force).Count -eq 0) {
                Remove-Item -LiteralPath $folder -Force
            }
        } catch { }
    }
    foreach ($path in $BackedUp) {
        try {
            $copy = Join-Path $Backup ($path.Replace('/', '\'))
            if ((Get-Sha $copy) -ne (Get-Sha (Get-Native $path))) { $problems++; Write-Log ($path + ' differs from its backup') }
        } catch {
            $problems++
        }
    }
    return $problems
}

function Invoke-Update {
    if (-not (Wait-App)) {
        Write-Result $false ('Lucense did not close within ' + $WaitSeconds + ' seconds, so nothing was changed.')
        return
    }
    if (Test-Path -LiteralPath $Backup) {
        Move-Item -LiteralPath $Backup -Destination ($Backup + '-' + [DateTime]::UtcNow.Ticks)
    }
    [void][System.IO.Directory]::CreateDirectory($Backup)
    $failure = $null
    try {
        $lines = Read-Plan
        Invoke-Swap $lines
        Invoke-Verify $lines
    } catch {
        $failure = $_.Exception.Message
    }
    if ($null -eq $failure) {
        Write-Result $true ''
        Write-Log ('updated to ' + $Version)
        return
    }
    Write-Log ('failed: ' + $failure)
    $problems = Invoke-Rollback
    if ($problems -gt 0) {
        $failure = $failure + ' Putting the old files back failed too; please reinstall Lucense with the installer. The old files are in ' + $Backup + '.'
    }
    Write-Result $false $failure
}

# Started as its own process with the user's environment, so it outlives this script.
function Start-App {
    try {
        Start-Process -FilePath (Get-Native $Launcher) -WorkingDirectory $Root
    } catch {
        Write-Log ('could not start Lucense: ' + $_.Exception.Message)
    }
}

# Arguments are checked before anything else: if they are wrong, the app and
# this script disagree, and nothing is touched.
if (@('run', 'run-elevated', 'swap') -notcontains $Mode) { Stop-Refused 'unknown mode' }
if ($AppPid -notmatch '^[0-9]+$') { Stop-Refused 'bad process id' }
if ($Version -notmatch '^[0-9]+(\.[0-9]+)*$') { Stop-Refused 'bad version' }
foreach ($path in @($Root, $Staging, $Plan, $Result)) { if (-not (Test-Absolute $path)) { Stop-Refused ('not a plain absolute path: ' + $path) } }
if (-not (Test-SafeRelative $Launcher)) { Stop-Refused 'bad launcher path' }
if (-not (Test-Path -LiteralPath (Get-Native $Launcher) -PathType Leaf)) { Stop-Refused 'no launcher in the install root' }
if (-not (Test-Path -LiteralPath $Files -PathType Container)) { Stop-Refused 'no staged files' }
if (-not $Plan.StartsWith($Staging + '\')) { Stop-Refused 'the plan is not in the staging folder' }

if ($Mode -eq 'run') {
    Write-State 'ready'
    try { Invoke-Update } catch { Write-Log ('helper failed: ' + $_.Exception.Message) }
    Start-App
    exit 0
}

if ($Mode -eq 'swap') {
    Write-State 'ready'
    try { Invoke-Update } catch {
        Write-Log ('helper failed: ' + $_.Exception.Message)
        try { Write-Result $false ('The update helper failed: ' + $_.Exception.Message) } catch { }
    }
    exit 0
}

# run-elevated: the swap runs in an administrator copy of this script (Windows
# shows its UAC prompt for it); Lucense is started afterwards from here, as the
# user. The elevated copy writes result.json and the backup into the user's
# own folder, which it was given, and files made there inherit that folder's
# permissions, so the user can read and remove them afterwards.
Remove-Quietly $StateFile
$self = $MyInvocation.MyCommand.Path
$quoted = @($Mode, $AppPid, $Root, $Staging, $Version, $Plan, $Result, $Launcher) | ForEach-Object { '"' + $_ + '"' }
$quoted[0] = '"swap"'
$argumentLine = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $self + '" ' + ($quoted -join ' ')
try {
    $elevated = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList $argumentLine -Verb RunAs -WindowStyle Hidden -Wait -PassThru
} catch {
    # The UAC prompt was dismissed, or this account may not elevate.
    Write-Log ('not elevated: ' + $_.Exception.Message)
    Write-State 'cancelled'
    exit 0
}
$state = ''
if (Test-Path -LiteralPath $StateFile) { $state = [System.IO.File]::ReadAllText($StateFile).Trim() }
if ($state -ne 'ready') {
    Write-State 'failed'
    exit 0
}
if (-not (Test-Path -LiteralPath $Result)) {
    Write-Result $false ('The update helper stopped unexpectedly (status ' + $elevated.ExitCode + ').')
}
Start-App
exit 0
