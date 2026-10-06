# Environment-agnostic Jepsen matrix cell runner (Windows GHA / self-hosted / local).
# Prefer PowerShell entrypoints; fall back to Git Bash for .sh-only cells.
# Usage:
#   .\run-jepsen-gha-cell.ps1 -Id C -Config 1dc-nochao
#   .\run-jepsen-gha-cell.ps1 -Id A -SkipRebuild
param(
  [Parameter(Mandatory = $true)][ValidatePattern('^[A-M]$')][string]$Id,
  [string]$Config = "",
  [int]$TimeLimit = 60,
  [int]$MinTimeLimit = 60,
  [switch]$SkipRebuild,
  [switch]$SkipDumpOnFail
)
$ErrorActionPreference = "Continue"
. (Join-Path $PSScriptRoot "jepsen-env.ps1")
Initialize-JepsenHostEnv
Clear-JepsenCellEnv

$JEPSEN_DIR = Get-JepsenDir
$ROOT = Get-JepsenRepoRoot
Set-Location $JEPSEN_DIR

if ($env:JEPSEN_TIME_LIMIT) {
  $TimeLimit = [int]$env:JEPSEN_TIME_LIMIT
}
if ($TimeLimit -lt $MinTimeLimit) {
  $TimeLimit = $MinTimeLimit
}
$env:JEPSEN_TIME_LIMIT = "$TimeLimit"
$env:MULTIDC_FULL = "1"
if ($SkipRebuild) {
  $env:MULTIDC_SKIP_REBUILD = "1"
  $env:JEPSEN_REBUILD = "0"
}

# id -> @{ config; kind=ps1|bash; path relative to repo; splat extras }
$cells = @{
  A = @{ config = "1dc-chaos"; kind = "ps1"; rel = "benchmarks/jepsen/scripts/run-jepsen.ps1"; scope = "1dc"; join = 0; swarm = 0; unclean = 0; qg = 0 }
  B = @{ config = "1dc-unclean-revive"; kind = "ps1"; rel = "benchmarks/jepsen/scripts/run-jepsen-unclean-revive.ps1"; scope = "1dc"; join = 0; swarm = 0; unclean = 1; qg = 0 }
  C = @{ config = "1dc-nochao"; kind = "ps1"; rel = "benchmarks/jepsen/scripts/run-jepsen-nochao.ps1"; scope = "1dc"; join = 0; swarm = 0; unclean = 0; qg = 1 }
  D = @{ config = "multidc-async-chaos"; kind = "ps1"; rel = "benchmarks/jepsen/multidc/scripts/run-multidc-async.ps1"; scope = "multidc"; join = 0; swarm = 0; unclean = 0; qg = 0; full = $true; noNem = $false }
  E = @{ config = "multidc-sync-chaos"; kind = "ps1"; rel = "benchmarks/jepsen/multidc/scripts/run-multidc-sync-voters.ps1"; scope = "multidc"; join = 0; swarm = 0; unclean = 0; qg = 0; full = $true; noNem = $false }
  F = @{ config = "multidc-async-nochao"; kind = "ps1"; rel = "benchmarks/jepsen/multidc/scripts/run-multidc-async.ps1"; scope = "multidc"; join = 0; swarm = 0; unclean = 0; qg = 0; full = $true; noNem = $true }
  G = @{ config = "multidc-sync-nochao"; kind = "ps1"; rel = "benchmarks/jepsen/multidc/scripts/run-multidc-sync-voters.ps1"; scope = "multidc"; join = 0; swarm = 0; unclean = 0; qg = 0; full = $true; noNem = $true }
  H = @{ config = "witness-chaos"; kind = "ps1"; rel = "benchmarks/jepsen/witness/scripts/run-witness-chaos.ps1"; scope = "multidc"; join = 0; swarm = 0; unclean = 0; qg = 0 }
  I = @{ config = "multidc-unclean-revive"; kind = "ps1"; rel = "benchmarks/jepsen/multidc/scripts/run-multidc-unclean-revive.ps1"; scope = "multidc"; join = 0; swarm = 0; unclean = 1; qg = 0 }
  J = @{ config = "1dc-swarm-chaos"; kind = "ps1"; rel = "benchmarks/jepsen/scripts/run-jepsen-swarm.ps1"; scope = "1dc"; join = 0; swarm = 1; unclean = 0; qg = 0 }
  K = @{ config = "1dc-join-shards"; kind = "ps1"; rel = "benchmarks/jepsen/scripts/run-jepsen-join.ps1"; scope = "1dc"; join = 1; swarm = 0; unclean = 0; qg = 0 }
  L = @{ config = "multidc-async-swarm"; kind = "ps1"; rel = "benchmarks/jepsen/multidc/scripts/run-multidc-swarm.ps1"; scope = "multidc"; join = 0; swarm = 1; unclean = 0; qg = 0; full = $true }
  M = @{ config = "multidc-async-join"; kind = "ps1"; rel = "benchmarks/jepsen/multidc/scripts/run-multidc-join.ps1"; scope = "multidc"; join = 1; swarm = 0; unclean = 0; qg = 0; full = $true }
}

$cell = $cells[$Id]
if (-not $cell) { throw "Unknown cell id: $Id" }
if ($Config -and $Config -ne $cell.config) {
  Write-Host "WARN: -Config $Config differs from canonical $($cell.config); using canonical"
}
$Config = $cell.config
$env:STAMP = if ($env:STAMP) { $env:STAMP } else { "$(Get-Date -Format yyyy-MM-dd)-gha-$Config" }

if ($cell.join -eq 1) { $env:JEPSEN_JOIN_SHARDS = "1" }
if ($cell.swarm -eq 1) { $env:JEPSEN_SWARM = "1" }
if ($cell.unclean -eq 1) {
  $env:JEPSEN_UNCLEAN_REVIVE = "1"
  if (-not $env:JEPSEN_UNCLEAN_DOWN_SEC) { $env:JEPSEN_UNCLEAN_DOWN_SEC = "15" }
}

# Pre-purge so ports/volumes do not collide across sequential cells on one Windows host.
$purge = Join-Path $PSScriptRoot "jepsen-purge.ps1"
if (Test-Path $purge) {
  Write-Host "Purge scope=$($cell.scope) before cell $Id"
  & $purge -Scope $cell.scope
}

$scriptPath = Join-Path $ROOT ($cell.rel -replace '/', [IO.Path]::DirectorySeparatorChar)
if (-not (Test-Path -LiteralPath $scriptPath)) {
  throw "Missing cell script: $scriptPath"
}

Write-Host "=== cell $Id config=$Config TL=$TimeLimit JEPSEN_M2=$($env:JEPSEN_M2) script=$scriptPath ==="

$splat = @{ TimeLimit = $TimeLimit }
$cmd = Get-Command $scriptPath -ErrorAction Stop
if ($SkipRebuild -and $cmd.Parameters.ContainsKey("SkipRebuild")) { $splat["SkipRebuild"] = $true }
if ($cell.ContainsKey("full") -and $cell.full -and $cmd.Parameters.ContainsKey("Full")) { $splat["Full"] = $true }
if ($cell.ContainsKey("noNem") -and $cell.noNem -and $cmd.Parameters.ContainsKey("NoNemesis")) { $splat["NoNemesis"] = $true }

$code = 1
try {
  & $scriptPath @splat
  $code = if ($null -eq $LASTEXITCODE) { 1 } else { [int]$LASTEXITCODE }
} catch {
  Write-Host "ERROR: cell $Id threw: $_"
  $code = 1
}

if ($cell.qg -eq 1 -and $code -eq 0) {
  $artifacts = Join-Path $JEPSEN_DIR "ARTIFACTS.txt"
  if (Test-Path $artifacts) {
    # ARTIFACTS.txt is KEY=VALUE shell exports; parse without sourcing bash.
    $reg = $null; $app = $null
    Get-Content $artifacts | ForEach-Object {
      if ($_ -match '^\s*REGISTER_HISTORY=(.+)\s*$') { $reg = $Matches[1].Trim('"') }
      if ($_ -match '^\s*APPEND_HISTORY=(.+)\s*$') { $app = $Matches[1].Trim('"') }
    }
    if ($reg -and $app) {
      Write-Host "qg-gate register=$reg append=$app"
      $qg = Join-Path $PSScriptRoot "qg-gate.ps1"
      & $qg -RegisterHistory $reg -AppendHistory $app -CiAdvisory
      if ($LASTEXITCODE -ne 0) { $code = $LASTEXITCODE }
    } else {
      Write-Host "ERROR: ARTIFACTS.txt missing REGISTER_HISTORY/APPEND_HISTORY"
      $code = 1
    }
  } else {
    Write-Host "ERROR: missing ARTIFACTS.txt after nochao cell"
    $code = 1
  }
}

if ($code -ne 0 -and -not $SkipDumpOnFail) {
  $dumpDir = Join-Path $JEPSEN_DIR "cluster-logs\$Id-$Config"
  $dumpPs1 = Join-Path $PSScriptRoot "dump-jepsen-cluster-logs.ps1"
  if (Test-Path $dumpPs1) {
    Write-Host "=== FAIL: dumping cluster logs -> $dumpDir ==="
    & $dumpPs1 -OutDir $dumpDir
  }
}

exit $code