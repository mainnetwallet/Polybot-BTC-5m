param(
    [switch]$NoBrowser,
    # By default start.bat does a fresh restart: any previous instance is stopped first.
    # Pass -NoRestart to keep an already-running healthy instance instead.
    [switch]$NoRestart,
    # By default start.bat stays open and Ctrl+C (or Q) stops everything. Pass -Detach to start the
    # servers in the background and return to the prompt right away (stop later with stop.bat).
    [switch]$Detach
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Logs = Join-Path $Root "logs"
$Pids = Join-Path $Logs "pids"
$VenvPython = Join-Path $Root ".venv\Scripts\python.exe"
$BackendDir = Join-Path $Root "backend"
$FrontendDir = Join-Path $Root "frontend"
$ApiUrl = "http://127.0.0.1:8000"
$UiUrl = "http://127.0.0.1:5173"

New-Item -ItemType Directory -Force -Path $Logs | Out-Null
New-Item -ItemType Directory -Force -Path $Pids | Out-Null

# The servers get an empty file as stdin. Otherwise Vite/node switches the shared console to raw
# input mode and swallows the keys typed in this terminal (Ctrl+C and typing then stop working).
# A previous run's server can still hold this file open (Windows then refuses to write it), so only
# (re)write it when it is missing or not empty, and fall back to a per-run file if it is locked.
$NullInput = Join-Path $Logs "empty.in"
$needsWrite = (-not (Test-Path $NullInput)) -or ((Get-Item $NullInput).Length -ne 0)
if ($needsWrite) {
    try {
        Set-Content -Path $NullInput -Value "" -NoNewline -Encoding ASCII
    }
    catch {
        $NullInput = Join-Path $Logs ("empty-" + $PID + ".in")
        Set-Content -Path $NullInput -Value "" -NoNewline -Encoding ASCII
    }
}

function Write-Step([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Fail([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

function Test-PortOpen([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(500)) {
            return $false
        }
        $client.EndConnect($pending)
        return $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Close()
    }
}

function Wait-Http([string]$Url, [int]$TimeoutSeconds, [int]$WatchProcess) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($WatchProcess -gt 0) {
            $alive = Get-Process -Id $WatchProcess -ErrorAction SilentlyContinue
            if (-not $alive) {
                return $false
            }
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return $true
            }
        }
        catch {
            # not ready yet
        }
        Start-Sleep -Milliseconds 750
    }
    return $false
}

function Show-Tail([string]$Path, [int]$LineCount = 25) {
    if (Test-Path $Path) {
        Write-Host "--- tail of $Path ---" -ForegroundColor DarkGray
        Get-Content -Path $Path -Tail $LineCount | ForEach-Object {
            Write-Host "    $_" -ForegroundColor DarkGray
        }
    }
}

function Save-Pid([string]$Name, [int]$ProcessId) {
    Set-Content -Path (Join-Path $Pids "$Name.pid") -Value $ProcessId -Encoding ASCII
}

function Stop-Tracked([int]$ProcessId) {
    if ($ProcessId -le 0) {
        return
    }
    $alive = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($alive) {
        & taskkill.exe /PID $ProcessId /T /F 2>$null | Out-Null
    }
}

function Remove-PidFile([string]$Name) {
    $path = Join-Path $Pids "$Name.pid"
    if (Test-Path $path) {
        Remove-Item $path -Force -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------
# Optional: Cloudflare quick tunnel + secret Gist link publisher (for the Android app).
# Everything below only runs when QUICK_TUNNEL=1 is set in .env. Without it the start-up is unchanged.
#   QUICK_TUNNEL=1   start a free Cloudflare quick tunnel to http://localhost:8000
#   GIST_TOKEN=...   GitHub classic token with ONLY the "gist" scope
#   GIST_ID=...      created on the first run and appended to .env automatically
# The tunnel URL changes on every run; the app reads the current one from the secret Gist ("gist:<id>").
# ---------------------------------------------------------------------------
function Read-DotEnv {
    $result = @{}
    $path = Join-Path $Root ".env"
    if (-not (Test-Path $path)) {
        return $result
    }
    foreach ($line in Get-Content -Path $path) {
        if ($line.TrimStart().StartsWith("#")) {
            continue
        }
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $result[$Matches[1]] = $Matches[2].Trim('"').Trim("'")
        }
    }
    return $result
}

# Sets KEY=value in .env (replaces an existing uncommented line, otherwise appends). UTF-8 without BOM,
# so non-ASCII characters in the file's comments survive.
function Set-DotEnvValue([string]$Key, [string]$Value) {
    $path = Join-Path $Root ".env"
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $text = ""
    if (Test-Path $path) {
        $text = [System.IO.File]::ReadAllText($path)
    }
    $pattern = '(?m)^[ \t]*' + [regex]::Escape($Key) + '[ \t]*=[^\r\n]*'
    if ([regex]::IsMatch($text, $pattern)) {
        $text = ([regex]$pattern).Replace($text, "$Key=$Value", 1)
    }
    else {
        if ($text.Length -gt 0 -and -not $text.EndsWith("`n")) {
            $text += [Environment]::NewLine
        }
        $text += "$Key=$Value" + [Environment]::NewLine
    }
    [System.IO.File]::WriteAllText($path, $text, $utf8)
}

function Read-SharedText([string]$Path) {
    try {
        $fs = [System.IO.File]::Open($Path, "Open", "Read", "ReadWrite")
        try {
            return (New-Object System.IO.StreamReader($fs)).ReadToEnd()
        }
        finally {
            $fs.Dispose()
        }
    }
    catch {
        return ""
    }
}

# Writes the tunnel URL to the file pc-url.txt of the secret Gist. Creates the Gist on the first run
# (or when the stored one no longer exists) and stores its id in .env. Returns the Gist id.
function Publish-Gist([string]$Url, [string]$Token, [string]$GistId) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $headers = @{ Authorization = "Bearer $Token"; "User-Agent" = "polybot-btc-5m"; Accept = "application/vnd.github+json" }
    $files = @{ "pc-url.txt" = @{ content = $Url } }
    if ($GistId) {
        $body = @{ files = $files } | ConvertTo-Json -Depth 5
        try {
            Invoke-RestMethod -Method Patch -Uri "https://api.github.com/gists/$GistId" -Headers $headers `
                -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType "application/json; charset=utf-8" | Out-Null
            return $GistId
        }
        catch {
            $code = 0
            if ($_.Exception.Response) {
                $code = [int]$_.Exception.Response.StatusCode
            }
            if ($code -ne 404) {
                throw
            }
            Write-Host "    Gist $GistId no longer exists; creating a new secret Gist." -ForegroundColor Yellow
        }
    }
    $body = @{ description = "polybot-btc-5m link"; public = $false; files = $files } | ConvertTo-Json -Depth 5
    $created = Invoke-RestMethod -Method Post -Uri "https://api.github.com/gists" -Headers $headers `
        -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType "application/json; charset=utf-8"
    Set-DotEnvValue "GIST_ID" ([string]$created.id)
    return [string]$created.id
}

function Stop-Tunnel {
    $path = Join-Path $Pids "cloudflared.pid"
    if (-not (Test-Path $path)) {
        return
    }
    $raw = Get-Content -Path $path -TotalCount 1 -ErrorAction SilentlyContinue
    $value = 0
    if ([int]::TryParse(([string]$raw).Trim(), [ref]$value) -and $value -gt 0) {
        $info = Get-CimInstance Win32_Process -Filter "ProcessId=$value" -ErrorAction SilentlyContinue
        if ($info -and ([string]$info.Name) -ieq "cloudflared.exe") {
            Stop-Tracked $value
        }
    }
    Remove-PidFile "cloudflared"
}

# Returns @{ Url; GistId } or $null when the tunnel is off, refused or failed. Never aborts the start-up.
function Start-QuickTunnel {
    $cfg = Read-DotEnv
    if ($cfg["QUICK_TUNNEL"] -ne "1") {
        return $null
    }

    Write-Step "Quick tunnel requested (QUICK_TUNNEL=1)"

    # Safety: never publish an API that has no token. The Android app authenticates with API_TOKEN.
    if (-not $cfg["API_TOKEN"] -or -not $cfg["API_TOKEN"].Trim()) {
        Write-Host "    Tunnel NOT started: API_TOKEN is empty in .env." -ForegroundColor Red
        Write-Host "    Without a token anyone who learns the tunnel link could control the bot." -ForegroundColor Red
        Write-Host "    Set a long random API_TOKEN in .env and run start.bat again." -ForegroundColor Yellow
        return $null
    }
    if (-not $cfg["GIST_TOKEN"] -or -not $cfg["GIST_TOKEN"].Trim()) {
        Write-Host "    Tunnel NOT started: GIST_TOKEN is empty in .env." -ForegroundColor Red
        Write-Host "    Without it the app cannot learn the new link after each restart. Create a classic GitHub token with ONLY the 'gist' scope." -ForegroundColor Yellow
        return $null
    }

    # Reminders (cloudflared connects to localhost, so the API must stay on loopback).
    $hostValue = [string]$cfg["HOST"]
    if ($hostValue -and @("127.0.0.1", "localhost", "::1") -notcontains $hostValue.Trim().ToLower()) {
        Write-Host "    WARNING: HOST=$hostValue in .env. Keep HOST=127.0.0.1: cloudflared connects locally and nothing else should reach the API directly." -ForegroundColor Yellow
    }
    else {
        Write-Host "    HOST must stay 127.0.0.1 (cloudflared connects locally)." -ForegroundColor DarkGray
    }
    if ([string]$cfg["ADMIN_PASSWORD"] -ceq "ChangeMe-123") {
        Write-Host "    Tunnel NOT started: ADMIN_PASSWORD is still the old example password (ChangeMe-123), which is public." -ForegroundColor Red
        Write-Host "    Set your own long password in .env (or leave it empty and rely on API_TOKEN) and run start.bat again." -ForegroundColor Yellow
        return $null
    }

    if (-not (Get-Command cloudflared -ErrorAction SilentlyContinue)) {
        Write-Host "    Installing cloudflared with winget..." -ForegroundColor Yellow
        & winget.exe install --id Cloudflare.cloudflared -e --accept-source-agreements --accept-package-agreements
        $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
    }
    $cloudflared = Get-Command cloudflared -ErrorAction SilentlyContinue
    if (-not $cloudflared) {
        Write-Host "    cloudflared is not available yet. Open a NEW terminal and run start.bat again (the bot itself is running without the tunnel)." -ForegroundColor Yellow
        return $null
    }

    # A tunnel from an earlier run would keep the old URL alive; replace it.
    Stop-Tunnel

    $tunnelLog = Join-Path $Logs "cloudflared.log"
    $tunnelOut = Join-Path $Logs "cloudflared.out.log"
    Remove-Item $tunnelLog, $tunnelOut -ErrorAction SilentlyContinue
    $proc = Start-Process -FilePath $cloudflared.Source `
        -ArgumentList @("tunnel", "--no-autoupdate", "--url", "http://localhost:8000") `
        -RedirectStandardInput $NullInput `
        -RedirectStandardError $tunnelLog `
        -RedirectStandardOutput $tunnelOut `
        -WindowStyle Hidden -PassThru
    Save-Pid "cloudflared" $proc.Id
    Write-Host "    cloudflared PID $($proc.Id)" -ForegroundColor DarkGray

    # cloudflared prints the link to stderr. Ignore api.trycloudflare.com, which only shows up in error lines.
    $url = $null
    for ($i = 0; $i -lt 45 -and -not $url; $i++) {
        Start-Sleep -Seconds 1
        if ($proc.HasExited) {
            break
        }
        foreach ($m in [regex]::Matches((Read-SharedText $tunnelLog), 'https://([a-z0-9-]+)\.trycloudflare\.com')) {
            if ($m.Groups[1].Value -ne "api") {
                $url = $m.Value
                break
            }
        }
    }
    if (-not $url) {
        Write-Host "    Could not get a tunnel link. See logs\cloudflared.log. The bot keeps running without the tunnel." -ForegroundColor Yellow
        Show-Tail $tunnelLog 10
        Stop-Tunnel
        return $null
    }
    Write-Host "    Tunnel link: $url" -ForegroundColor Green

    $gistId = $null
    try {
        $gistId = Publish-Gist $url $cfg["GIST_TOKEN"].Trim() ([string]$cfg["GIST_ID"]).Trim()
    }
    catch {
        Write-Host "    Could not update the Gist: $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host "    Check that GIST_TOKEN is a classic token with the 'gist' scope. The link above still works if you type it into the app." -ForegroundColor Yellow
        return @{ Url = $url; GistId = $null }
    }

    # A fresh quick-tunnel hostname needs a few seconds before it resolves; wait a little, but never fail.
    $reachable = $false
    for ($i = 0; $i -lt 20 -and -not $reachable; $i++) {
        try {
            $r = Invoke-WebRequest -Uri "$url/healthz" -UseBasicParsing -TimeoutSec 4
            $reachable = ($r.StatusCode -eq 200)
        }
        catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $reachable) {
        Write-Host "    The tunnel does not answer yet; it usually needs a few more seconds before the phone can use it." -ForegroundColor DarkGray
    }
    return @{ Url = $url; GistId = $gistId }
}

Write-Host ""
Write-Host "Polymarket BTC 5m bot" -ForegroundColor Green
Write-Host "Starting backend and dashboard" -ForegroundColor DarkGray
Write-Host ""

if (-not $NoRestart) {
    # Stop the old server first (also frees the venv files before setup/pip touch them).
    # stop.ps1 only kills processes it can prove belong to this project folder.
    Write-Step "Stopping any previous instance (fresh restart)"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "stop.ps1")
    Write-Host ""
}

$needsSetup = $false
if (-not (Test-Path $VenvPython)) { $needsSetup = $true }
if (-not (Test-Path (Join-Path $FrontendDir "node_modules"))) { $needsSetup = $true }
if (-not (Test-Path (Join-Path $Root ".env"))) { $needsSetup = $true }

if ($needsSetup) {
    Write-Step "First run detected - running setup"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "setup.ps1")
    if ($LASTEXITCODE -ne 0) {
        Fail "Setup did not complete. Run setup.bat and fix the reported problem."
    }
}

$apiOpen = Test-PortOpen 8000
$uiOpen = Test-PortOpen 5173

$apiHealthy = $false
if ($apiOpen) {
    $apiHealthy = Wait-Http "$ApiUrl/api/health" 3 0
    if (-not $apiHealthy) {
        Fail "Port 8000 is already in use by another process that does not answer /api/health. Close it and retry."
    }
}

$uiHealthy = $false
if ($uiOpen) {
    $uiHealthy = Wait-Http "$UiUrl/" 3 0
    if (-not $uiHealthy) {
        Fail "Port 5173 is already in use by another process that does not serve the dashboard. Close it and retry."
    }
}

$backendPid = 0
$frontendPid = 0
$startedBackend = $false
$startedFrontend = $false

if ($apiHealthy) {
    Write-Host "    API already running on 127.0.0.1:8000" -ForegroundColor DarkGray
}
else {
    Write-Step "Starting API (FastAPI) on 127.0.0.1:8000"
    $backendOut = Join-Path $Logs "backend.out.log"
    $backendErr = Join-Path $Logs "backend.err.log"
    $proc = Start-Process -FilePath $VenvPython `
        -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000", "--no-access-log") `
        -WorkingDirectory $BackendDir `
        -RedirectStandardInput $NullInput `
        -RedirectStandardOutput $backendOut `
        -RedirectStandardError $backendErr `
        -WindowStyle Hidden -PassThru
    $backendPid = $proc.Id
    $startedBackend = $true
    Save-Pid "backend" $backendPid
    Write-Host "    PID $backendPid" -ForegroundColor DarkGray
}

if ($uiHealthy) {
    Write-Host "    Dashboard already running on 127.0.0.1:5173" -ForegroundColor DarkGray
}
else {
    $viteEntry = Join-Path $FrontendDir "node_modules\vite\bin\vite.js"
    if (-not (Test-Path $viteEntry)) {
        Fail "Vite is not installed in frontend\node_modules. Run setup.bat and retry."
    }
    Write-Step "Starting dashboard (Vite) on 127.0.0.1:5173"
    $frontendOut = Join-Path $Logs "frontend.out.log"
    $frontendErr = Join-Path $Logs "frontend.err.log"
    $proc = Start-Process -FilePath "node.exe" `
        -ArgumentList @($viteEntry) `
        -WorkingDirectory $FrontendDir `
        -RedirectStandardInput $NullInput `
        -RedirectStandardOutput $frontendOut `
        -RedirectStandardError $frontendErr `
        -WindowStyle Hidden -PassThru
    $frontendPid = $proc.Id
    $startedFrontend = $true
    Save-Pid "frontend" $frontendPid
    Write-Host "    PID $frontendPid" -ForegroundColor DarkGray
}

if ($startedBackend) {
    Write-Step "Waiting for the API to answer /api/health"
    if (-not (Wait-Http "$ApiUrl/api/health" 45 $backendPid)) {
        Show-Tail $backendErr
        Stop-Tracked $backendPid
        Remove-PidFile "backend"
        if ($startedFrontend) {
            Stop-Tracked $frontendPid
            Remove-PidFile "frontend"
        }
        Fail "The backend did not start within 45 seconds. See logs\backend.err.log and logs\backend.log."
    }
    Write-Host "    API ready" -ForegroundColor DarkGray
}

if ($startedFrontend) {
    Write-Step "Waiting for the dashboard"
    if (-not (Wait-Http "$UiUrl/" 45 $frontendPid)) {
        Show-Tail $frontendErr
        Stop-Tracked $frontendPid
        Remove-PidFile "frontend"
        if ($startedBackend) {
            Stop-Tracked $backendPid
            Remove-PidFile "backend"
        }
        Fail "The dashboard did not start within 45 seconds. See logs\frontend.err.log."
    }
    Write-Host "    Dashboard ready" -ForegroundColor DarkGray
}

$tunnelInfo = $null
try {
    $tunnelInfo = Start-QuickTunnel
}
catch {
    Write-Host "    Tunnel setup failed: $($_.Exception.Message). The bot keeps running without the tunnel." -ForegroundColor Yellow
}

$mode = "paper"
try {
    $health = Invoke-RestMethod -Uri "$ApiUrl/api/health" -TimeoutSec 5
    $mode = [string]$health.mode
}
catch {
    # mode display is cosmetic
}

Write-Host ""
Write-Host "Polymarket BTC 5m bot is running." -ForegroundColor Green
Write-Host "    Dashboard: $UiUrl" -ForegroundColor Gray
Write-Host "    API docs:  $ApiUrl/docs" -ForegroundColor Gray
Write-Host "    Mode:      $($mode.ToUpper())" -ForegroundColor Gray
Write-Host "    Logs:      logs\backend.log, logs\frontend.out.log" -ForegroundColor DarkGray
if ($tunnelInfo) {
    Write-Host "    Tunnel:    $($tunnelInfo.Url)" -ForegroundColor Gray
    if ($tunnelInfo.GistId) {
        Write-Host "    In the app enter: gist:$($tunnelInfo.GistId)" -ForegroundColor Green
    }
}
if ($Detach) {
    Write-Host "    Stop:      stop.bat" -ForegroundColor DarkGray
}
else {
    Write-Host "    Stop:      press Ctrl+C (or Q) here, or run stop.bat" -ForegroundColor DarkGray
}
Write-Host ""

if (-not $NoBrowser) {
    Start-Process $UiUrl
}

if ($Detach) {
    exit 0
}

# Stay attached: Ctrl+C / Q stops the API and the dashboard. Ctrl+C is read as a normal key
# (no console signal), so the batch file never asks "Terminate batch job (Y/N)?".
$interactive = $true
try {
    [Console]::TreatControlCAsInput = $true
}
catch {
    $interactive = $false
}

$stopRequested = $false
try {
    while (-not $stopRequested) {
        if ($interactive) {
            try {
                while ([Console]::KeyAvailable) {
                    $key = [Console]::ReadKey($true)
                    $ctrlC = ($key.Key -eq [ConsoleKey]::C) -and (($key.Modifiers -band [ConsoleModifiers]::Control) -ne 0)
                    if ($ctrlC -or $key.Key -eq [ConsoleKey]::Q) {
                        $stopRequested = $true
                    }
                }
            }
            catch {
                $interactive = $false
            }
        }
        if ($stopRequested) { break }
        # stop.bat from another window (or a crash) ends the servers: nothing left to watch.
        if (-not (Test-PortOpen 8000) -and -not (Test-PortOpen 5173)) {
            Write-Host "Servers are no longer running." -ForegroundColor Yellow
            Stop-Tunnel
            break
        }
        Start-Sleep -Milliseconds 400
    }
}
finally {
    try { [Console]::TreatControlCAsInput = $false } catch { }
}

if ($stopRequested) {
    Write-Host ""
    Write-Step "Stopping Polymarket BTC 5m bot"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "stop.ps1")
}

exit 0
