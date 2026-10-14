param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$toolDir = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force $toolDir | Out-Null
try { $lockStream = [IO.File]::Open((Join-Path $toolDir 'startup.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
catch { throw 'Another startup is in progress. Please wait.' }
$started = @()
try {
    foreach ($command in @('java','node','npm.cmd')) { Get-Command $command -ErrorAction Stop | Out-Null }
    & (Join-Path $PSScriptRoot 'stop-dev.ps1')
    foreach ($port in @(8080,5173)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is occupied by another service; it has not been stopped." }
    }
    $maven = Join-Path $toolDir 'apache-maven-3.9.11\bin\mvn.cmd'
    if (-not (Test-Path $maven)) { $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source }
    $backendDir=Join-Path $projectRoot 'backend'
    $frontendDir=Join-Path $projectRoot 'frontend'
    $jar=Join-Path $backendDir 'target\knowledge-hub-0.1.0.jar'
    $sources=@(Get-ChildItem (Join-Path $backendDir 'src') -File -Recurse) + @(Get-Item (Join-Path $backendDir 'pom.xml'))
    $rebuild= -not (Test-Path $jar)
    if (-not $rebuild) { $rebuild=@($sources | Where-Object { $_.LastWriteTimeUtc -gt (Get-Item $jar).LastWriteTimeUtc }).Count -gt 0 }
    if ($rebuild) {
        Push-Location $backendDir
        try { & $maven -B package; if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' } } finally { Pop-Location }
    }
    Push-Location $frontendDir
    try {
        $lockHash=(Get-FileHash package-lock.json -Algorithm SHA256).Hash
        $stamp=Join-Path $toolDir 'npm-lock.sha256'
        if (-not (Test-Path 'node_modules\vite\bin\vite.js') -or -not (Test-Path $stamp) -or (Get-Content $stamp -Raw).Trim() -ne $lockHash) {
            & npm.cmd ci
            if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
            Set-Content $stamp $lockHash
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    $passwordFile=Join-Path $toolDir 'dev-password.txt'
    if (-not $env:ADMIN_PASSWORD) {
        if (-not (Test-Path $passwordFile)) { Set-Content $passwordFile ('Dev-'+[Guid]::NewGuid().ToString('N')) -NoNewline }
        $env:ADMIN_PASSWORD=Get-Content $passwordFile -Raw
    }
    $backend=Start-Process java -ArgumentList '-jar',('"'+$jar+'"') -WorkingDirectory $backendDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $toolDir 'backend.log') -RedirectStandardError (Join-Path $toolDir 'backend-error.log')
    $started += $backend
    $ready=$false
    for ($attempt=0;$attempt -lt 60;$attempt++) {
        if ($backend.HasExited) { throw 'Backend exited. See .tools/backend.log and backend-error.log.' }
        try { $health=Invoke-RestMethod 'http://localhost:8080/actuator/health' -TimeoutSec 2; if ($health.status -eq 'UP') { $ready=$true; break } } catch {}
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Backend did not become healthy within the startup timeout.' }
    $vite=Join-Path $frontendDir 'node_modules\vite\bin\vite.js'
    $frontend=Start-Process node -ArgumentList ('"'+$vite+'"'),'--host','127.0.0.1','--port','5173','--strictPort' -WorkingDirectory $frontendDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $toolDir 'frontend.log') -RedirectStandardError (Join-Path $toolDir 'frontend-error.log')
    $started += $frontend
    $ready=$false
    for ($attempt=0;$attempt -lt 30;$attempt++) {
        if ($frontend.HasExited) { throw 'Frontend exited. See .tools/frontend-error.log.' }
        try { $page=Invoke-WebRequest 'http://localhost:5173' -UseBasicParsing -TimeoutSec 2; if ($page.StatusCode -eq 200) { $ready=$true; break } } catch {}
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Frontend did not become ready.' }
    Write-Host 'READY: http://localhost:5173'
    Write-Host 'Account: admin. Initial generated password: .tools/dev-password.txt (existing passwords are unchanged).'
    Write-Host 'Stop both services by double-clicking Stop.cmd. Data is preserved.'
    if (-not $NoBrowser) { Start-Process 'http://localhost:5173' }
} catch {
    foreach ($service in $started) { if (-not $service.HasExited) { Stop-Process -InputObject $service -ErrorAction SilentlyContinue } }
    throw
} finally { $lockStream.Dispose() }
