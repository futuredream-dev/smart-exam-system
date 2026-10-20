param([ValidateRange(1,65535)][int]$Port = 3306)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$script = Join-Path $root 'scripts/verify-test-database.ps1'
if (!(Test-Path -LiteralPath $script)) { throw 'Isolation preflight is not implemented.' }
$pwsh = (Get-Command pwsh).Source
function Invoke-Check([string[]]$ExtraArgs) {
    $text = & $pwsh -NoProfile -File $script @ExtraArgs 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    [pscustomobject]@{ExitCode=$exitCode;Text=$text}
}
$wrong = Invoke-Check @('-ExpectedDatabase','smart_exam')
if ($wrong.ExitCode -eq 0 -or $wrong.Text -notmatch 'DB_TARGET_MISMATCH') { throw 'Wrong target must be rejected before connecting.' }
$closed = Invoke-Check @('-Port','1')
if ($closed.ExitCode -eq 0 -or $closed.Text -notmatch 'CONNECTION_FAILED') { throw 'An unavailable server must fail closed.' }
$safe = Invoke-Check @('-Port', [string]$Port)
if ($safe.ExitCode -ne 0 -or $safe.Text -notmatch '"safe"\s*:\s*true') { throw 'The dedicated account must pass isolation preflight.' }
Write-Output 'PASS 3 isolation preflight scenarios'
