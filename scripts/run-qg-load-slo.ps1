<#
.SYNOPSIS
  DEPRECATED: forwards to scripts/run-jmeter-load-slo.ps1 (Apache JMeter + grid-sql-client).

.DESCRIPTION
  Former QG load-SLO scaffold. Use run-jmeter-load-slo.ps1 directly.
  Calm host only - never parallel with QG / Jepsen / OSS peers / JMH.
#>
param(
  [string]$Stamp = "",
  [int]$Clients = 8,
  [int]$DurationSec = 30,
  [double]$TpsFloor = 0,
  [double]$P95CeilingUs = 0,
  [double]$P99CeilingUs = 0,
  [switch]$DrySmoke,
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Write-Warning "run-qg-load-slo.ps1 is DEPRECATED - forwarding to run-jmeter-load-slo.ps1 (JMeter JavaSampler / grid-sql-client)."
$forward = Join-Path $PSScriptRoot "run-jmeter-load-slo.ps1"
$params = @{
  Clients = $Clients
  DurationSec = $DurationSec
}
if ($Stamp) { $params["Stamp"] = $Stamp }
if ($TpsFloor -gt 0) { $params["TpsFloor"] = $TpsFloor }
if ($P95CeilingUs -gt 0) { $params["P95CeilingUs"] = $P95CeilingUs }
if ($P99CeilingUs -gt 0) { $params["P99CeilingUs"] = $P99CeilingUs }
if ($DrySmoke) { $params["DrySmoke"] = $true }
if ($SkipBuild) { $params["SkipBuild"] = $true }
& $forward @params
exit $LASTEXITCODE