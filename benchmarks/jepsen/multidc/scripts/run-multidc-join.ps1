param(
  [switch]$Full,
  [int]$TimeLimit = 60,
  [switch]$SkipRebuild,
  [switch]$Fast
)
$ErrorActionPreference = "Continue"
if ($Fast) { $SkipRebuild = $true; if (-not $PSBoundParameters.ContainsKey("TimeLimit") -and -not $env:JEPSEN_TIME_LIMIT) { $TimeLimit = 40 } }
if ($env:JEPSEN_TIME_LIMIT) { $TimeLimit = [int]$env:JEPSEN_TIME_LIMIT }
$JEPSEN_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $SkipRebuild) {
  Write-Host "Building jamoa-grid-jepsen:local (host-jar)..."
  & (Join-Path $JEPSEN_DIR "scripts\build-jepsen-image.ps1")
  if ($LASTEXITCODE -ne 0) { throw "build-jepsen-image failed: $LASTEXITCODE" }
}
$env:MULTIDC_FULL = "1"
$env:MULTIDC_SKIP_REBUILD = "1"
$env:JEPSEN_TIME_LIMIT = "$TimeLimit"
$env:JEPSEN_JOIN_SHARDS = "1"
$env:JEPSEN_SWARM = ""
$env:MULTIDC_WORKLOADS = "join"
$env:MULTIDC_NEMESIS = "1"
if (-not $env:STAMP -or $env:STAMP -eq "") { $env:STAMP = (Get-Date -Format "yyyy-MM-dd") + "-multidc-async-join-shards" }
$bash = $null
foreach ($c in @("C:\Program Files\Git\bin\bash.exe","C:\Program Files\Git\usr\bin\bash.exe")) {
  if (Test-Path $c) { $bash = $c; break }
}
if (-not $bash) { throw "Git bash required for run-multidc-join.ps1" }
& $bash (Join-Path $PSScriptRoot "run-multidc-join.sh")
exit $LASTEXITCODE
