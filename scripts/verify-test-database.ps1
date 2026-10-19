param(
    [string]$ExpectedDatabase = 'smart_exam_opt_test_20261019',
    [string]$HostName = '127.0.0.1',
    [ValidateRange(1,65535)][int]$Port = 3306
)
$ErrorActionPreference = 'Stop'
$fixedDatabase = 'smart_exam_opt_test_20261019'
function Reject([string]$Reason) {
    [ordered]@{safe=$false;reasonCode=$Reason} | ConvertTo-Json -Compress | Write-Output
    exit 1
}
if ($ExpectedDatabase -cne $fixedDatabase) { Reject 'DB_TARGET_MISMATCH' }
if ($HostName -notin @('127.0.0.1','localhost')) { Reject 'NONLOCAL_DATABASE_HOST' }
if ([string]::IsNullOrWhiteSpace($env:TEST_DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:TEST_DB_PASSWORD)) { Reject 'MISSING_TEST_CREDENTIALS' }
$client = Get-Command mysql.exe -ErrorAction SilentlyContinue
if (!$client) { Reject 'MYSQL_CLIENT_UNAVAILABLE' }
$info = [Diagnostics.ProcessStartInfo]::new($client.Source)
$info.UseShellExecute = $false; $info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
$info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
$info.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
foreach ($arg in @('--no-defaults','--protocol=TCP',"--host=$HostName","--port=$Port","--user=$($env:TEST_DB_USERNAME)","--database=$ExpectedDatabase",'--connect-timeout=3','--batch','--raw','--skip-column-names','--execute=SELECT DATABASE(); SHOW GRANTS;')) { $info.ArgumentList.Add($arg) }
$info.Environment['MYSQL_PWD'] = $env:TEST_DB_PASSWORD
try {
    $process = [Diagnostics.Process]::Start($info)
    $outTask = $process.StandardOutput.ReadToEndAsync()
    $errTask = $process.StandardError.ReadToEndAsync()
    if (!$process.WaitForExit(10000)) { $process.Kill(); Reject 'CONNECTION_FAILED' }
    $text = $outTask.GetAwaiter().GetResult()
    $null = $errTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { Reject 'CONNECTION_FAILED' }
} catch { Reject 'CONNECTION_FAILED' }
$lines = @($text -split "`r?`n" | Where-Object { $_ -ne '' })
if ($lines.Count -lt 2 -or $lines[0] -cne $fixedDatabase) { Reject 'DB_TARGET_MISMATCH' }
$escapedDatabase = $fixedDatabase.Replace('_','\_')
$scoped = $false
foreach ($line in $lines[1..($lines.Count-1)]) {
    if ($line -match '(?i)WITH GRANT OPTION') { Reject 'UNSAFE_PRIVILEGES' }
    if ($line -match '^GRANT USAGE ON \*\.\* TO ') { continue }
    $grant = [regex]::Match($line, '^GRANT (.+?) ON `([^`]+)`\.\* TO ')
    if (!$grant.Success -or $grant.Groups[2].Value -cne $escapedDatabase) { Reject 'UNSAFE_PRIVILEGES' }
    $scoped = $true
}
if (!$scoped) { Reject 'MISSING_TEST_PRIVILEGES' }
[ordered]@{safe=$true;database=$fixedDatabase;scopedPrivileges=$true;businessTablesRead=$false} | ConvertTo-Json -Compress
exit 0
