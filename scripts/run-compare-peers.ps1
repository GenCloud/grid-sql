param([string]$Stamp = "")
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $Stamp) { $Stamp = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { "2026-09-18-oss-peers" } }
$env:JMH_STAMP = $Stamp
Write-Host "=== run-compare-peers stamp=$Stamp (calm host; Ignite then Geode sequential) ==="
& (Join-Path $PSScriptRoot "run-compare-ignite.ps1") -Stamp $Stamp
& (Join-Path $PSScriptRoot "run-compare-geode.ps1") -Stamp $Stamp
$matrix = Join-Path $PSScriptRoot "write-peer-matrix.ps1"
if (Test-Path $matrix) { & $matrix -Stamp $Stamp }
Write-Host "run-compare-peers done"