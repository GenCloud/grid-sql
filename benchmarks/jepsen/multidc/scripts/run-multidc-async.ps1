# Multi-DC Jepsen - ASYNC_SHIP entry (scaffold validate, or -Full / MULTIDC_FULL=1 for Docker+lein).
param(
  [switch]$Full,
  [int]$TimeLimit = 60,
  [switch]$NoNemesis,
  [switch]$SkipRebuild
)
$ErrorActionPreference = "Continue"
& (Join-Path $PSScriptRoot "run-multidc-full.ps1") -Mode async -Full:$Full -TimeLimit $TimeLimit -NoNemesis:$NoNemesis -SkipRebuild:$SkipRebuild
exit $LASTEXITCODE
