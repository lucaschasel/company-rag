param([switch]$BackendOnly, [switch]$FrontendOnly)

$ErrorActionPreference = 'Stop'
if ($BackendOnly -and $FrontendOnly) { throw 'Choose either -BackendOnly or -FrontendOnly, not both.' }
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$jarPath = Join-Path $projectRoot 'backend\target\knowledge-hub-0.1.0.jar'
$vitePath = Join-Path $projectRoot 'frontend\node_modules\vite\bin\vite.js'
$jarPattern = '(?:^|\s)-jar\s+"?' + [regex]::Escape($jarPath) + '"?(?:\s|$)'
$vitePattern = [regex]::Escape((Join-Path $projectRoot 'frontend\node_modules\')) + '(?:\.bin\\+\.\.\\)?vite\\bin\\vite\.js'
$mavenPattern = [regex]::Escape('-Dmaven.multiModuleProjectDirectory=' + (Join-Path $projectRoot 'backend')) + '(?:"|\s)'

# Match the actual project entry point; never stop all java/node processes.
$snapshot = @(Get-CimInstance Win32_Process)
$roots = @($snapshot | Where-Object {
    (-not $FrontendOnly -and $_.Name -eq 'java.exe' -and ($_.CommandLine -match $jarPattern -or ($_.CommandLine -match $mavenPattern -and $_.CommandLine -match '\bspring-boot:run\b'))) -or
    (-not $BackendOnly -and $_.Name -eq 'node.exe' -and $_.CommandLine -match $vitePattern)
})
$targets = @($roots)
$frontier = @($roots)
while ($frontier.Count -gt 0) {
    $parents = @($frontier.ProcessId)
    $next = @($snapshot | Where-Object { $_.ParentProcessId -in $parents -and $_.ProcessId -notin @($targets.ProcessId) })
    $targets = @($next) + @($targets)
    $frontier = $next
}
foreach ($target in $targets) {
    $process = Get-Process -Id $target.ProcessId -ErrorAction SilentlyContinue
    if ($process -and ($process.StartTime.ToUniversalTime() - $target.CreationDate.ToUniversalTime()).Duration().TotalMilliseconds -lt 10) {
        Stop-Process -InputObject $process -ErrorAction Stop
        $process.WaitForExit(10000) | Out-Null
        Write-Host "Stopped project service: $($target.Name) (PID $($target.ProcessId))"
    }
}
if ($targets.Count -eq 0) { Write-Host 'No background services started by start-dev.ps1 found.' }
if (-not $BackendOnly) {
    # A surviving esbuild child can keep its executable locked on Windows.
    $esbuildPath = Join-Path $projectRoot 'frontend\node_modules\@esbuild\win32-x64\esbuild.exe'
    $children = @(Get-CimInstance Win32_Process -Filter "Name = 'esbuild.exe'" | Where-Object { $_.ExecutablePath -eq $esbuildPath })
    foreach ($child in $children) {
        $process = Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
        if ($process -and ($process.StartTime.ToUniversalTime() - $child.CreationDate.ToUniversalTime()).Duration().TotalMilliseconds -lt 10) {
            Stop-Process -InputObject $process -ErrorAction Stop
            if (-not $process.WaitForExit(10000)) { throw "esbuild did not exit: $($child.ProcessId)" }
            Write-Host "Stopped project esbuild: PID $($child.ProcessId)"
        }
    }
}
Write-Host 'Database files, uploads and credentials are preserved.'
