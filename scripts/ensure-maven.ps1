$ErrorActionPreference = 'Stop'
# 只在缺少 PATH/MAVEN_HOME 时使用，不安装系统组件，不修改用户环境变量。
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cache = Join-Path $root '.runtime\tools'
$maven = Join-Path $cache 'apache-maven-3.9.9\bin\mvn.cmd'
if (Test-Path -LiteralPath $maven) { return $maven }
New-Item -ItemType Directory -Path $cache -Force | Out-Null
$archive = Join-Path $cache 'apache-maven-3.9.9-bin.zip'
$url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
Write-Host '正在从 Maven Central 获取独立 Maven 3.9.9，并校验 SHA-512…'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest $url -UseBasicParsing -OutFile $archive
$expected = ((Invoke-WebRequest ($url + '.sha512') -UseBasicParsing).Content.Trim() -split '\s+')[0]
$sha = [Security.Cryptography.SHA512]::Create()
$stream = [IO.File]::OpenRead($archive)
try { $actual = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','') }
finally { $stream.Dispose(); $sha.Dispose() }
if ($expected.Length -ne 128 -or $actual -ne $expected) { throw 'Maven 下载校验失败，未执行下载内容。请检查网络后重试。' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    foreach ($entry in $zip.Entries) {
        $destination = [IO.Path]::GetFullPath((Join-Path $cache $entry.FullName))
        if (-not $destination.StartsWith($cache + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Maven 压缩包包含不安全路径。' }
        if (-not $entry.Name) { [IO.Directory]::CreateDirectory($destination) | Out-Null; continue }
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
    }
} finally { $zip.Dispose() }
if (-not (Test-Path -LiteralPath $maven)) { throw 'Maven 解压失败。' }
return $maven
