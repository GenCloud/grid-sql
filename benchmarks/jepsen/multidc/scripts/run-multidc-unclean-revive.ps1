# Multi-DC unclean-revive (PowerShell host). Invokes bash entry via Git Bash when available.
param(
  [int]$TimeLimit = 60,
  [switch]$SkipRebuild,
  [switch]$Fast,
  [ValidateSet("async", "sync-voters")][string]$Mode = "async"
)
$ErrorActionPreference = "Continue"
$JepsenRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $JepsenRoot "scripts\jepsen-env.ps1")
Initialize-JepsenHostEnv
if ($Fast) { $SkipRebuild = $true }
if ($env:JEPSEN_TIME_LIMIT) { $TimeLimit = [int]$env:JEPSEN_TIME_LIMIT }
$env:JEPSEN_TIME_LIMIT = "$TimeLimit"
$env:MULTIDC_FULL = "1"
$env:MULTIDC_NEMESIS = "1"
$env:JEPSEN_UNCLEAN_REVIVE = "1"
if (-not $env:JEPSEN_UNCLEAN_DOWN_SEC) { $env:JEPSEN_UNCLEAN_DOWN_SEC = "15" }
$env:MULTIDC_MODE = $Mode
$env:MULTIDC_SKIP_REBUILD = if ($SkipRebuild) { "1" } else { "0" }
if (-not $env:STAMP) { $env:STAMP = (Get-Date -Format "yyyy-MM-dd") + "-multidc-unclean-revive" }
Remove-Item Env:JEPSEN_JOIN_SHARDS -ErrorAction SilentlyContinue
Remove-Item Env:JEPSEN_SWARM -ErrorAction SilentlyContinue
if (-not $env:MULTIDC_WORKLOADS) { $env:MULTIDC_WORKLOADS = "register,append" }

$sh = Join-Path $PSScriptRoot "run-multidc-unclean-revive.sh"
$code = Invoke-JepsenBash -ScriptPath $sh
exit $(if ($null -eq $code) { 1 } else { $code })