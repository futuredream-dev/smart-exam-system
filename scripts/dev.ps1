param([ValidateSet('start','stop','check')][string]$Action = 'check')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime = Join-Path $root '.runtime'
$stateFile = Join-Path $runtime 'dev-processes.json'
$entries = @()

function Tool([string]$name) {
    $found = Get-Command $name -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    return $null
}
function Tcp([string]$server, [int]$port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.ConnectAsync($server, $port)
        if (-not $pending.Wait(1500)) { return $false }
        return $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}
function Version([string]$program, [string]$option) {
    if (-not $program) { return '未安装' }
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        return ((& $program $option 2>&1 | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    } finally { $ErrorActionPreference = $previous }
}
function BackendReady {
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 3
        return $health.status -eq 'UP'
    } catch { return $false }
}
function FrontendReady {
    try {
        $response = Invoke-WebRequest 'http://127.0.0.1:5173/' -UseBasicParsing -TimeoutSec 3
        return $response.StatusCode -eq 200 -and $response.Content -match '/src/main.js'
    } catch { return $false }
}
function SaveState {
    # 只保存 PID/创建时间/程序路径，不写环境变量或秘密。
    @{ root=$root; processes=@($script:entries) } | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath $stateFile -Encoding UTF8
}
function StopOwned {
    if (-not (Test-Path -LiteralPath $stateFile)) {
        Write-Host '没有本脚本记录的进程；不会按端口或名称结束其他程序。'
        return
    }
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    if ($state.root -ne $root) { throw 'PID 文件不属于当前项目目录，拒绝停止。' }
    foreach ($entry in @($state.processes)) {
        $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if (-not $process) { continue }
        # PID 可能被复用：同时匹配创建时刻和程序路径，避免误杀。
        if ($process.StartTime.ToUniversalTime().Ticks.ToString() -ne $entry.started -or $process.Path -ne $entry.executable) {
            Write-Warning "$($entry.name) 的 PID 已被复用或身份不符，未停止。"
            continue
        }
        Stop-Process -Id $process.Id -ErrorAction Stop
        $process.WaitForExit(5000) | Out-Null
        Write-Host "已停止本项目 $($entry.name)。"
    }
    Remove-Item -LiteralPath $stateFile
}
function Launch([string]$name, [string]$exe, [string]$arguments, [string]$directory) {
    # 直接启动 Java/Node，避免 cmd 或 Maven 子进程残留。
    $launchOptions = @{
        FilePath=$exe; ArgumentList=$arguments; WorkingDirectory=$directory
        WindowStyle='Hidden'; PassThru=$true
        RedirectStandardOutput=(Join-Path $runtime "$name.log")
        RedirectStandardError=(Join-Path $runtime "$name-error.log")
    }
    $process = Start-Process @launchOptions
    $script:entries += @{ name=$name; pid=$process.Id; started=$process.StartTime.ToUniversalTime().Ticks.ToString(); executable=$exe }
    SaveState
    return $process
}
function WaitReady([string]$name, $process, [scriptblock]$probe) {
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) { throw "$name 提前退出，请检查 .runtime 下的日志。" }
        if (& $probe) { return }
        Start-Sleep -Milliseconds 700
    }
    throw "$name 启动超时，请检查数据库账号、初始化状态和 .runtime 下的日志。"
}
# 同一路径一次只允许一个启停操作，避免 PID 文件竞争。
$hash = [Security.Cryptography.SHA256]::Create()
$key = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($root))).Replace('-','')
$mutex = New-Object Threading.Mutex($false, "Local\SmartExamDev-$key")
$locked = $false
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw '另一个启动/停止操作正在执行，请稍后重试。' }
    if ($Action -eq 'stop') { StopOwned; exit 0 }
    $java = Tool 'java.exe'; $node = Tool 'node.exe'; $pnpm = Tool 'pnpm.cmd'
    $maven = Tool 'mvn.cmd'
    if (-not $maven -and $env:MAVEN_HOME) {
        $candidate = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
        if (Test-Path -LiteralPath $candidate) { $maven = $candidate }
    }
    if (-not $maven) {
        $cachedMaven = Join-Path $runtime 'tools\apache-maven-3.9.9\bin\mvn.cmd'
        if (Test-Path -LiteralPath $cachedMaven) { $maven = $cachedMaven }
    }
    $javaVersion = Version $java '-version'
    $nodeVersion = Version $node '--version'
    Write-Host "Java: $javaVersion"
    Write-Host "Node: $nodeVersion"
    Write-Host ('pnpm: ' + (Version $pnpm '--version'))
    Write-Host ('Maven: ' + $(if ($maven) { '可用' } else { '未检测到；首次启动将下载独立 Maven 到 .runtime/tools' }))
    $services = @(Get-Service -Name '*mysql*' -ErrorAction SilentlyContinue)
    foreach ($service in $services) { Write-Host "MySQL 服务 $($service.Name)：$($service.Status)" }
    if (-not $services.Count) { Write-Host '未找到 MySQL 命名服务，将检查配置地址（也支持独立运行实例）。' }
    if (-not $env:DB_HOST) { $env:DB_HOST='127.0.0.1' }
    if (-not $env:DB_PORT) { $env:DB_PORT='3306' }
    if (-not $env:DB_NAME) { $env:DB_NAME='smart_exam' }
    if (-not $env:DB_USERNAME) { $env:DB_USERNAME='root' }
    $databaseReachable = Tcp $env:DB_HOST ([int]$env:DB_PORT)
    foreach ($port in @(3306,8080,5173)) {
        Write-Host "本机端口 $port：$(if (Tcp '127.0.0.1' $port) {'正在监听'} else {'未监听'})"
    }
    Write-Host "数据库：$(if ($databaseReachable) {'正常（TCP 可达；账号和表结构仍需实际校验）'} else {'异常（无法连接配置地址）'})"
    Write-Host "后端：$(if (BackendReady) {'正常（包含真实数据库健康检查）'} else {'未启动或健康检查异常'})"
    Write-Host "前端：$(if (FrontendReady) {'正常'} else {'未启动或不是本项目 Vite 页面'})"
    foreach ($name in @('DB_PASSWORD','JWT_SECRET')) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { Write-Host "未检测到 $name，请先配置本机环境变量。" }
    }
    if ($Action -eq 'check') { exit 0 }
    if (-not $java -or $javaVersion -notmatch 'version "17\.') { throw '需要 JDK 17，请配置 JAVA_HOME 和 PATH。' }
    if (-not $node -or $nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 20) { throw '需要 Node.js 20 或更高版本。' }
    if (-not $pnpm) { throw '未检测到 pnpm，请先执行 npm install -g pnpm。' }
    if (-not $env:DB_PASSWORD) { throw '未检测到 DB_PASSWORD，请先配置本机环境变量。' }
    if (-not $env:JWT_SECRET) { throw '未检测到 JWT_SECRET，请先配置至少32位随机密钥的本机环境变量。' }
    if ($env:JWT_SECRET.Length -lt 32 -or $env:JWT_SECRET -like 'change_me*') { throw 'JWT_SECRET 需要至少32位随机字符，不能使用示例值。' }
    if (-not $databaseReachable) { throw 'MySQL 未就绪。请先启动 MySQL 服务，并确认 DB_HOST/DB_PORT。' }
    if ((Tcp '127.0.0.1' 8080) -or (Tcp '127.0.0.1' 5173)) { throw '8080 或 5173 已占用。请先停止已有实例；本脚本不会误杀占用端口的程序。' }
    if (Test-Path -LiteralPath $stateFile) { StopOwned }
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    if (-not $maven) { $maven = & (Join-Path $PSScriptRoot 'ensure-maven.ps1') }
    Write-Host '正在打包后端（不依赖 IDEA）；测试请另行执行 mvn test。'
    Push-Location (Join-Path $root 'backend')
    try { & $maven -B -ntp -DskipTests package; if ($LASTEXITCODE -ne 0) { throw 'Maven 打包失败。' } } finally { Pop-Location }
    $vite = Join-Path $root 'frontend\node_modules\vite\bin\vite.js'
    if (-not (Test-Path -LiteralPath $vite)) {
        Push-Location (Join-Path $root 'frontend')
        try { & $pnpm install --frozen-lockfile; if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' } } finally { Pop-Location }
    }
    $env:SERVER_PORT='8080'
    $jar = Join-Path $root 'backend\target\smart-exam-backend-0.0.1-SNAPSHOT.jar'
    try {
        $backend = Launch 'backend' $java ('-Duser.timezone=Asia/Shanghai -jar "'+$jar+'"') (Join-Path $root 'backend')
        WaitReady '后端' $backend { BackendReady }
        $frontend = Launch 'frontend' $node ('"'+$vite+'" --host 127.0.0.1 --port 5173 --strictPort') (Join-Path $root 'frontend')
        WaitReady '前端' $frontend { FrontendReady }
    } catch { StopOwned; throw }
    Write-Host '启动成功：http://localhost:5173 （后端 8080）'
    Write-Host '日志位于 .runtime；关闭本窗口不会停止服务。停止请运行 stop-dev.bat。'
} catch {
    Write-Host ('失败：' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
    $hash.Dispose()
}
