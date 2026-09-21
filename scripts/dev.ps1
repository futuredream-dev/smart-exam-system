param(
    [ValidateSet('start','stop','check','backend','frontend')][string]$Action = 'check',
    [switch]$NoBrowser
)
# 统一启动/停止/状态入口：VS Code 任务、start-dev.bat、手工调用都复用本脚本。
# 端口约定：MySQL 3306 / 后端 8080 / 前端 5173；5174 出现即视为启动异常。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

# ---------------------------------------------------------------------------
# 冷启动必须先过这一关（否则“卡住”在起子进程之前）：
# 当进程环境块里同时存在大小写重复键（Path/PATH、http_proxy/HTTP_PROXY、
# https_proxy/HTTPS_PROXY —— 从 Git Bash 派生或由宿主注入的 shell 很常见）时，
# PowerShell 5.1 的 Start-Process 会在复制环境变量阶段直接抛
#   Item has already been added. Key in dictionary: 'Path'  Key being added: 'PATH'
# 结果是后端和前端一个都起不来。这里在脚本最开头就把重复键折叠掉，
# 并对 Start-Process 做一次真实自检，确保后续 Launch 不会失败。
# ---------------------------------------------------------------------------
function Reset-DuplicateEnvironment {
    for ($pass = 1; $pass -le 3; $pass++) {
        $raw = [Environment]::GetEnvironmentVariables()
        $seen = @{}
        $duplicates = @{}
        foreach ($key in @($raw.Keys | ForEach-Object { $_.ToString() })) {
            $lower = $key.ToLowerInvariant()
            if ($seen.ContainsKey($lower)) { $duplicates[$lower] = $seen[$lower] } else { $seen[$lower] = $key }
        }
        if ($duplicates.Count -eq 0) { return }
        foreach ($lower in @($duplicates.Keys)) {
            $canonical = $duplicates[$lower]
            $value = [Environment]::GetEnvironmentVariable($canonical, 'Process')
            if ($null -eq $value) { continue }
            [Environment]::SetEnvironmentVariable($canonical, $null, 'Process')
            [Environment]::SetEnvironmentVariable($canonical, $value, 'Process')
        }
    }
}

function Assert-StartProcessUsable {
    $probe = Join-Path $env:TEMP ('smartexam-startprobe-' + [Guid]::NewGuid().ToString('N') + '.txt')
    try {
        $process = Start-Process -FilePath $env:ComSpec -ArgumentList '/c echo ok' `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $probe -ErrorAction Stop
        $null = $process.WaitForExit(5000)
    } catch {
        throw ("无法创建子进程：{0}。请确认 VS Code 是从开始菜单/资源管理器启动，而不是从 Git Bash 里直接 code . 启动。" -f $_.Exception.Message)
    } finally {
        try { if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe -Force -ErrorAction Stop } } catch { }
    }
}

Reset-DuplicateEnvironment

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime = Join-Path $root '.runtime'
$stateFile = Join-Path $runtime 'dev-processes.json'
$script:entries = @()
$script:startedNew = $false

$backendPort = 8080
$frontendPort = 5173
$strayPort = 5174
$backendJar = Join-Path $root 'backend\target\smart-exam-backend-0.0.1-SNAPSHOT.jar'
$viteEntry = Join-Path $root 'frontend\node_modules\vite\bin\vite.js'
$loginUrl = "http://localhost:$frontendPort/login"

function Import-LocalEnv([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Write-Host 'config/local.env 不存在，沿用当前进程已有的环境变量。'
        return
    }
    foreach ($line in Get-Content -LiteralPath $path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) { continue }
        $name = $trimmed.Substring(0, $separator).Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { continue }
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($value.Length -ge 2) {
            $first = $value[0]
            $last = $value[$value.Length - 1]
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        $existing = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrWhiteSpace($existing)) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
    Write-Host '已加载本地环境配置。'
}

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

function Get-PortOwner([int]$port) {
    $conn = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) | Select-Object -First 1
    if (-not $conn) { return $null }
    $procId = [int]$conn.OwningProcess
    $cim = Get-CimInstance Win32_Process -Filter ("ProcessId={0}" -f $procId) -ErrorAction SilentlyContinue
    $name = if ($cim) { $cim.Name } else { (Get-Process -Id $procId -ErrorAction SilentlyContinue).ProcessName }
    $command = if ($cim) { [string]$cim.CommandLine } else { '' }
    if (-not $command) { $command = '(无法读取命令行)' }
    return [pscustomobject]@{ port = $port; processId = $procId; name = $name; command = $command }
}

# PID 数字会被系统复用，必须再确认进程命令行仍指向本项目目录。
function Test-ProjectOwned($owner, [string]$kind) {
    if (-not $owner) { return $false }
    $command = [string]$owner.command
    if ($command.IndexOf($root, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    if ($kind -eq 'backend' -and $command -match 'com\.smartexam\.SmartExamApplication') { return $true }
    return $false
}

function Describe($owner) {
    if (-not $owner) { return '(无)' }
    return ("PID={0} 进程={1} 命令行={2}" -f $owner.processId, $owner.name, $owner.command)
}

# 用原生 socket 发一个最小 HTTP GET：不经过系统代理，且连接/读取都有硬超时。
# Invoke-WebRequest / Invoke-RestMethod 会走系统代理，代理环境下会出现
# “正在读取 Web 响应 (读取的字节数: 0)” 长时间无响应，把等待循环一起拖住。
function HttpGet([int]$port, [string]$path) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.ConnectAsync('127.0.0.1', $port)
        if (-not $pending.Wait(1500)) { return $null }
        $client.ReceiveTimeout = 2500
        $client.SendTimeout = 2500
        $stream = $client.GetStream()
        $request = "GET $path HTTP/1.1`r`nHost: 127.0.0.1:$port`r`nAccept: */*`r`nConnection: close`r`n`r`n"
        $payload = [Text.Encoding]::ASCII.GetBytes($request)
        $stream.Write($payload, 0, $payload.Length)
        $stream.Flush()
        $buffer = New-Object byte[] 8192
        $builder = New-Object Text.StringBuilder
        $deadline = (Get-Date).AddSeconds(4)
        while ((Get-Date) -lt $deadline -and $builder.Length -lt 131072) {
            $read = 0
            try { $read = $stream.Read($buffer, 0, $buffer.Length) } catch { break }
            if ($read -le 0) { break }
            [void]$builder.Append([Text.Encoding]::ASCII.GetString($buffer, 0, $read))
        }
        if ($builder.Length -eq 0) { return $null }
        return $builder.ToString()
    } catch { return $null } finally { $client.Dispose() }
}

function BackendReady {
    $response = HttpGet $backendPort '/actuator/health'
    if ($null -eq $response) { return $false }
    return $response -match '"status"\s*:\s*"UP"'
}

function FrontendReady {
    $response = HttpGet $frontendPort '/'
    if ($null -eq $response) { return $false }
    return ($response -match '(?m)^HTTP/1\.\d\s+200') -and ($response -match '/src/main\.js')
}

function FrontendHttpCode {
    $response = HttpGet $frontendPort '/'
    if ($null -eq $response) { return 0 }
    $match = [regex]::Match($response, '(?m)^HTTP/1\.\d\s+(\d{3})')
    if ($match.Success) { return [int]$match.Groups[1].Value }
    return 0
}

function Get-Identity([int]$target) {
    $process = Get-Process -Id $target -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    $started = ''
    try { $started = $process.StartTime.ToUniversalTime().Ticks.ToString() } catch { $started = '' }
    return @{ started = $started }
}

function Add-Entry([string]$name, [int]$target) {
    $identity = Get-Identity $target
    $script:entries += @{ name = $name; pid = $target; started = $(if ($identity) { $identity.started } else { '' }) }
}

function SaveState {
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    @{
        root = $root
        backendPort = $backendPort
        frontendPort = $frontendPort
        processes = @($script:entries)
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stateFile -Encoding UTF8
}

function Stop-Owned {
    if (-not (Test-Path -LiteralPath $stateFile)) {
        Write-Host '没有本脚本记录的进程；不会按端口或名称结束其他程序。'
        return
    }
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    if ($state.root -ne $root) { throw 'PID 文件不属于当前项目目录，拒绝停止。' }
    foreach ($entry in @($state.processes)) {
        $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if (-not $process) { continue }
        $owned = $false
        try {
            if ($process.StartTime.ToUniversalTime().Ticks.ToString() -eq [string]$entry.started) {
                $cim = Get-CimInstance Win32_Process -Filter ("ProcessId={0}" -f $entry.pid) -ErrorAction SilentlyContinue
                $command = if ($cim) { [string]$cim.CommandLine } else { '' }
                if ($command.IndexOf($root, [StringComparison]::OrdinalIgnoreCase) -ge 0) { $owned = $true }
            }
        } catch { $owned = $false }
        if (-not $owned) {
            Write-Warning "$($entry.name) 的 PID 已被复用或已不属于本项目，未停止。"
            continue
        }
        Stop-Process -Id $process.Id -ErrorAction Stop
        $process.WaitForExit(5000) | Out-Null
        Write-Host "已停止本项目 $($entry.name)（PID $($entry.pid)）。"
    }
    # 状态文件只是缓存（Ensure-* 以端口占用为准，SaveState 每次重写）。
    # 删除失败不影响“已停止”这一事实，因此只提示，不判为失败。
    try {
        Remove-Item -LiteralPath $stateFile -Force -ErrorAction Stop
    } catch {
        Write-Warning "状态文件未能删除（$($_.Exception.Message)），下次启动会覆盖重写。"
    }
}

# 清理历史遗留的 5174 漂移实例：只结束确认属于本项目的进程。
function Clear-StrayFrontend([switch]$Strict) {
    $stray = Get-PortOwner $strayPort
    if (-not $stray) { return }
    if (Test-ProjectOwned $stray 'frontend') {
        Stop-Process -Id $stray.processId -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 600
        Write-Host "已清理异常漂移的前端实例（$strayPort，PID $($stray.processId)）。"
        return
    }
    $message = "$strayPort 被占用（启动异常）：$(Describe $stray)"
    if ($Strict) { throw ($message + '  本脚本不会结束未知进程，请手动处理后再重试。') }
    Write-Warning $message
}

function Launch([string]$name, [string]$exe, [string]$arguments, [string]$directory) {
    # 直接启动 Java/Node，避免 cmd 或 Maven 子进程残留。
    $launchOptions = @{
        FilePath = $exe; ArgumentList = $arguments; WorkingDirectory = $directory
        WindowStyle = 'Hidden'; PassThru = $true
        RedirectStandardOutput = (Join-Path $runtime "$name.log")
        RedirectStandardError = (Join-Path $runtime "$name-error.log")
    }
    $process = Start-Process @launchOptions
    Add-Entry $name $process.Id
    SaveState
    $script:startedNew = $true
    return $process
}

function WaitReady([string]$name, $process, [scriptblock]$probe) {
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        if ($process -and $process.HasExited) { throw "$name 提前退出，请检查 .runtime 下的日志。" }
        if (& $probe) { return }
        Start-Sleep -Milliseconds 700
    }
    throw "$name 启动超时，请检查数据库账号、初始化状态和 .runtime 下的日志。"
}

function Assert-Toolchain {
    $java = Tool 'java.exe'
    $node = Tool 'node.exe'
    $pnpm = Tool 'pnpm.cmd'
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
    if (-not $java -or $javaVersion -notmatch 'version "17\.') { throw '缺少 Java 17，请配置 JAVA_HOME 和 PATH。' }
    if (-not $node -or $nodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 20) { throw '需要 Node.js 20 或更高版本。' }
    if (-not $pnpm) { throw '未找到 pnpm，请先执行 npm install -g pnpm。' }
    return @{ java = $java; node = $node; pnpm = $pnpm; maven = $maven }
}

function Assert-MySql {
    $services = @(Get-Service -Name '*mysql*' -ErrorAction SilentlyContinue)
    if ($services.Count) {
        foreach ($service in $services) { Write-Host "MySQL 服务 $($service.Name)：$($service.Status)" }
        $down = @($services | Where-Object { $_.Status -ne 'Running' })
        if ($down.Count) { throw ("MySQL 未运行：$($down.Name -join ',')。本脚本不会启动或重启 MySQL 服务。") }
    } else {
        Write-Host '未找到 MySQL 命名服务，将按配置地址检查（也支持独立运行实例）。'
    }
    if (-not $env:DB_HOST) { $env:DB_HOST = '127.0.0.1' }
    if (-not $env:DB_PORT) { $env:DB_PORT = '3306' }
    if (-not $env:DB_NAME) { $env:DB_NAME = 'smart_exam' }
    if (-not $env:DB_USERNAME) { $env:DB_USERNAME = 'root' }
    if (-not (Tcp $env:DB_HOST ([int]$env:DB_PORT))) {
        throw "MySQL 未就绪：$($env:DB_HOST):$($env:DB_PORT) 无法连接，请先启动 MySQL 服务。"
    }
    Write-Host "MySQL 可连接：$($env:DB_HOST):$($env:DB_PORT)"
}

function Ensure-Backend($tools) {
    $owner = Get-PortOwner $backendPort
    if ($owner) {
        if (-not (Test-ProjectOwned $owner 'backend')) {
            Write-Host "[错误] 端口 $backendPort 已被其他程序占用，启动失败。" -ForegroundColor Red
            Write-Host "  $(Describe $owner)" -ForegroundColor Red
            Write-Host '  本脚本不会结束未知进程，请手动处理后再重试。' -ForegroundColor Red
            exit 1
        }
        if (-not (BackendReady)) {
            Write-Host "端口 $backendPort 已属于本项目（PID $($owner.processId)），等待健康检查就绪…"
            WaitReady '后端' $null { BackendReady }
        }
        Write-Host "后端已运行：PID $($owner.processId)（$backendPort）"
        Add-Entry 'backend' $owner.processId
        return
    }
    if (-not $tools.maven) {
        $tools.maven = & (Join-Path $PSScriptRoot 'ensure-maven.ps1')
    }
    Write-Host '正在打包后端（不依赖 IDEA）；测试请另行执行 mvn test。'
    Push-Location (Join-Path $root 'backend')
    try { & $tools.maven -B -ntp -DskipTests package; if ($LASTEXITCODE -ne 0) { throw 'Maven 打包失败。' } } finally { Pop-Location }
    $env:SERVER_PORT = "$backendPort"
    $backend = Launch 'backend' $tools.java ('-Duser.timezone=Asia/Shanghai -jar "' + $backendJar + '"') (Join-Path $root 'backend')
    WaitReady '后端' $backend { BackendReady }
    Write-Host "后端已启动：PID $($backend.Id)（$backendPort，health=UP）"
}

function Ensure-Frontend($tools) {
    $owner = Get-PortOwner $frontendPort
    if ($owner) {
        if (-not (Test-ProjectOwned $owner 'frontend')) {
            Write-Host "[错误] 端口 $frontendPort 已被其他程序占用，启动失败。" -ForegroundColor Red
            Write-Host "  $(Describe $owner)" -ForegroundColor Red
            Write-Host '  本脚本不会结束未知进程，请手动处理后再重试。' -ForegroundColor Red
            exit 1
        }
        if (-not (FrontendReady)) {
            Write-Host "端口 $frontendPort 已属于本项目（PID $($owner.processId)），等待页面就绪…"
            WaitReady '前端' $null { FrontendReady }
        }
        Write-Host "前端已运行：PID $($owner.processId)（$frontendPort）"
        Add-Entry 'frontend' $owner.processId
        return
    }
    if (-not (Test-Path -LiteralPath $viteEntry)) {
        Push-Location (Join-Path $root 'frontend')
        try { & $tools.pnpm install --frozen-lockfile; if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' } } finally { Pop-Location }
    }
    $arguments = '"' + $viteEntry + '" --host 127.0.0.1 --port ' + $frontendPort + ' --strictPort'
    $frontend = Launch 'frontend' $tools.node $arguments (Join-Path $root 'frontend')
    WaitReady '前端' $frontend { FrontendReady }
    Write-Host "前端已启动：PID $($frontend.Id)（$frontendPort，HTTP 200）"
}

function Show-Status {
    $ready = $true
    $services = @(Get-Service -Name '*mysql*' -ErrorAction SilentlyContinue)
    $mysqlState = if ($services.Count) { ($services | ForEach-Object { "$($_.Name) $($_.Status)" }) -join '; ' } else { 'NoService' }
    $mysqlPort = if (Tcp '127.0.0.1' 3306) { 'Listening' } else { 'NOT LISTENING' }
    $backendOwner = Get-PortOwner $backendPort
    $frontendOwner = Get-PortOwner $frontendPort
    $strayOwner = Get-PortOwner $strayPort
    $backend = BackendReady
    $frontendCode = FrontendHttpCode

    Write-Host '[MySQL]'
    Write-Host "Service: $mysqlState"
    Write-Host "3306: $mysqlPort"
    Write-Host ''
    Write-Host '[Backend]'
    Write-Host ("PID: " + $(if ($backendOwner) { $backendOwner.processId } else { '-' }))
    Write-Host "$($backendPort): $(if ($backendOwner) { 'Listening' } else { 'NOT LISTENING' })"
    Write-Host "Health: $(if ($backend) { 'UP' } else { 'DOWN' })"
    Write-Host ''
    Write-Host '[Frontend]'
    Write-Host ("PID: " + $(if ($frontendOwner) { $frontendOwner.processId } else { '-' }))
    Write-Host "$($frontendPort): $(if ($frontendOwner) { 'Listening' } else { 'NOT LISTENING' })"
    Write-Host "HTTP: $(if ($frontendCode -eq 200) { '200' } else { '不可用' })"
    Write-Host ''
    Write-Host '[Unexpected]'
    Write-Host "$($strayPort): $(if ($strayOwner) { 'LISTENING' } else { 'NOT LISTENING' })"
    Write-Host ''

    $reasons = @()
    if ($strayOwner) { $reasons += "$strayPort 出现监听（启动异常）"; $ready = $false }
    if ($mysqlPort -ne 'Listening') { $reasons += '3306 未监听'; $ready = $false }
    if ($mysqlState -ne 'NoService' -and $mysqlState -notmatch 'Running') { $reasons += 'MySQL 服务未运行'; $ready = $false }
    if (-not $backendOwner) { $reasons += '后端未启动'; $ready = $false }
    elseif (-not $backend) { $reasons += '后端健康检查未通过'; $ready = $false }
    if (-not $frontendOwner) { $reasons += '前端未启动'; $ready = $false }
    elseif ($frontendCode -ne 200) { $reasons += '前端页面不可用'; $ready = $false }

    Write-Host 'Overall:'
    if ($ready) {
        Write-Host 'READY'
    } elseif (-not $backendOwner -and -not $frontendOwner -and -not $strayOwner) {
        Write-Host 'STOPPED'
    } else {
        Write-Host 'NOT READY'
    }
    if ($reasons.Count) { Write-Host ('原因：' + ($reasons -join '；')) }
    return $ready
}

# 同一路径一次只允许一个启停操作，避免 PID 文件竞争。
$hash = [Security.Cryptography.SHA256]::Create()
$key = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($root))).Replace('-', '')
$mutex = New-Object Threading.Mutex($false, "Local\SmartExamDev-$key")
$locked = $false
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw '另一个启动/停止操作正在执行，请稍后重试。' }

    if ($Action -eq 'stop') {
        Stop-Owned
        Clear-StrayFrontend
        Write-Host ''
        Show-Status | Out-Null
        Write-Host ''
        Write-Host '已停止：仅关闭本项目 backend / frontend，MySQL267 保持运行。'
        exit 0
    }

    Import-LocalEnv (Join-Path $root 'config\local.env')
    if ($Action -eq 'check') {
        $ok = Show-Status
        exit $(if ($ok) { 0 } else { 1 })
    }

    $tools = Assert-Toolchain
    if (-not $env:DB_PASSWORD) { throw '未检测到 DB_PASSWORD，请先配置本机环境变量。' }
    if (-not $env:JWT_SECRET) { throw '未检测到 JWT_SECRET，请先配置至少 32 位随机密钥的本机环境变量。' }
    if ($env:JWT_SECRET.Length -lt 32 -or $env:JWT_SECRET -like 'change_me*') { throw 'JWT_SECRET 需要至少 32 位随机字符，不能使用示例值。' }
    Assert-MySql

    # 真正启动子进程前的最后一次自检：环境块已折叠，Start-Process 必须可用。
    Assert-StartProcessUsable

    # 步骤 8：启动前先确认 5174 不存在；已存在的本项目漂移实例会被清理。
    Clear-StrayFrontend -Strict

    try {
        if ($Action -eq 'backend') {
            Ensure-Backend $tools
        } elseif ($Action -eq 'frontend') {
            Ensure-Frontend $tools
        } else {
            Ensure-Backend $tools
            Ensure-Frontend $tools
            Clear-StrayFrontend -Strict
        }
    } catch { Stop-Owned; throw }

    SaveState
    Write-Host ''
    if ($Action -eq 'start') {
        if ($script:startedNew) {
            Write-Host '本次已启动缺失的服务。'
        } else {
            Write-Host 'Backend 已运行'
            Write-Host 'Frontend 已运行'
        }
    }
    $ok = Show-Status
    if ($Action -eq 'start') {
        Write-Host ''
        Write-Host ('System ' + $(if ($ok) { 'READY' } else { 'NOT READY' }))
        if ($ok) {
            Write-Host "入口：$loginUrl"
            if ($script:startedNew -and -not $NoBrowser) {
                Start-Process $loginUrl | Out-Null
                Write-Host '已打开浏览器。'
            }
        }
    }
    if (-not $ok) { exit 1 }
} catch {
    Write-Host ('失败：' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
    $hash.Dispose()
}
