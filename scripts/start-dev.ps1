param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'launch.ps1') -NoBrowser:$NoBrowser
