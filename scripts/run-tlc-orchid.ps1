param(
  [switch]$Heavy,
  [switch]$RegionClaim
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Tools = Join-Path $Root ".tools"
$Jar = Join-Path $Tools "tla2tools.jar"
$Url = if ($env:TLA2TOOLS_URL) { $env:TLA2TOOLS_URL } else { "https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar" }
$OutDir = Join-Path $Root "docs\spec\orchid\tlc-out"
New-Item -ItemType Directory -Force -Path $Tools, $OutDir | Out-Null

if (-not (Test-Path $Jar)) {
  Write-Host "Downloading tla2tools.jar ..."
  Invoke-WebRequest -Uri $Url -OutFile $Jar
}

Set-Location (Join-Path $Root "docs\spec\orchid")

function Invoke-TlcSpec {
  param([string]$SpecName, [string]$CfgName, [string]$LogName)
  Write-Host "Running TLC on $SpecName ($CfgName) ..."
  $log = Join-Path $OutDir $LogName
  & java -XX:+UseParallelGC -cp $Jar tlc2.TLC -config $CfgName -workers auto -maxSetSize 1000000 $SpecName 2>&1 | Tee-Object -FilePath $log
  $content = Get-Content $log -Raw
  if ($content -notmatch "Model checking completed\. No error has been found\.") {
    throw "TLC failed for $SpecName ($CfgName). See $log"
  }
  Write-Host "TLC OK: $SpecName ($CfgName)"
}

# Hard gate: fast MaxSeq=3 (OrchidLog.cfg ≡ OrchidLog-fast.cfg)
Invoke-TlcSpec -SpecName "OrchidLog.tla" -CfgName "OrchidLog.cfg" -LogName "last-run.log"
Invoke-TlcSpec -SpecName "OrchidLogMultiDc.tla" -CfgName "OrchidLogMultiDc.cfg" -LogName "last-run-multidc.log"
if ($Heavy) {
  Invoke-TlcSpec -SpecName "OrchidLog.tla" -CfgName "OrchidLog-heavy.cfg" -LogName "last-run-heavy.log"
}
# Always hard: RegionClaim (TD-SPEC-001) — after OrchidLog + MultiDc
Invoke-TlcSpec -SpecName "RegionClaim.tla" -CfgName "RegionClaim.cfg" -LogName "last-run-region-claim.log"
if ($RegionClaim) {
  Write-Host "RegionClaim already included in hard gate (-RegionClaim is no-op alias)"
}
Write-Host "TLC OK (1-DC + multi-DC + RegionClaim$(if ($Heavy) { ' + heavy' } else { '' }))"
