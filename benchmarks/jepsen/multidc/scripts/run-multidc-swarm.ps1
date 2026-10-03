param(
  [switch]$Full,
  [int]$TimeLimit = 60,
  [switch]$SkipRebuild,
  [switch]$Fast
)
$ErrorActionPreference = "Continue"
if ($Fast) { $SkipRebuild = $true; if (-not $PSBoundParameters.ContainsKey("TimeLimit") -and -not $env:JEPSEN_TIME_LIMIT) { $TimeLimit = 45 } }
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
$env:JEPSEN_SWARM = "1"
$env:JEPSEN_JOIN_SHARDS = ""
$env:MULTIDC_WORKLOADS = "append"
$env:MULTIDC_NEMESIS = "1"
if (-not $env:STAMP -or $env:STAMP -eq "") { $env:STAMP = (Get-Date -Format "yyyy-MM-dd") + "-multidc-async-swarm-chaos" }
$bash = $null
foreach ($c in @("C:\Program Files\Git\bin\bash.exe","C:\Program Files\Git\usr\bin\bash.exe")) {
  if (Test-Path $c) { $bash = $c; break }
}
if (-not $bash) { throw "Git bash required for run-multidc-swarm.ps1" }
& $bash (Join-Path $PSScriptRoot "run-multidc-swarm.sh")
exit $LASTEXITCODE
