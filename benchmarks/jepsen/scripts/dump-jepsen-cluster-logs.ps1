# Dump Jepsen cluster docker logs for FAIL triage (Windows / any host — no bash required).
# Usage: dump-jepsen-cluster-logs.ps1 [-OutDir path]
# Env: STAMP, JEPSEN_INSTANCE, JEPSEN_1DC_CTR_PREFIX, JEPSEN_MDC_CTR_PREFIX, FAIL_CLASS, HARNESS_REASON
param(
  [string]$OutDir = ""
)
$ErrorActionPreference = "Continue"
. (Join-Path $PSScriptRoot "jepsen-env.ps1")
Initialize-JepsenHostEnv
$JEPSEN_DIR = Get-JepsenDir
$stamp = if ($env:STAMP) { $env:STAMP } else { Get-Date -Format "yyyyMMdd-HHmmss" }
$inst = if ($env:JEPSEN_INSTANCE) { "-$($env:JEPSEN_INSTANCE)" } else { "" }
if (-not $OutDir -or $OutDir.Trim().Length -eq 0) {
  $OutDir = Join-Path $JEPSEN_DIR "cluster-logs\$stamp$inst"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $JEPSEN_DIR "cluster-logs") | Out-Null

$prefixes = New-Object System.Collections.Generic.List[string]
if ($env:JEPSEN_1DC_CTR_PREFIX) { [void]$prefixes.Add($env:JEPSEN_1DC_CTR_PREFIX) }
if ($env:JEPSEN_MDC_CTR_PREFIX) { [void]$prefixes.Add($env:JEPSEN_MDC_CTR_PREFIX) }
[void]$prefixes.Add("jamoa-jepsen")
[void]$prefixes.Add("jamoa-multidc")
$unique = @($prefixes | Select-Object -Unique)

Write-Host "dump-jepsen-cluster-logs -> $(Get-JepsenSafeLogPath $OutDir)"
$psLines = @(
  "stamp=$stamp instance=$($env:JEPSEN_INSTANCE)",
  "time=$(Get-Date -Format o)",
  "prefixes=$($unique -join ' ')"
)
try {
  $psLines += @(docker ps -a --format "table {{.Names}}\t{{.Status}}\t{{.Image}}" 2>$null)
} catch { $psLines += "docker ps failed" }
[IO.File]::WriteAllLines((Join-Path $OutDir "docker-ps.txt"), $psLines)

function Get-ContainerNames([string[]]$Prefs) {
  $all = @()
  try { $all = @(docker ps -a --format "{{.Names}}" 2>$null) } catch { return @() }
  $matched = New-Object System.Collections.Generic.List[string]
  foreach ($name in $all) {
    if (-not $name) { continue }
    foreach ($p in $Prefs) {
      if ($name -eq $p -or $name.StartsWith("$p-")) {
        [void]$matched.Add($name)
        break
      }
    }
  }
  return @($matched | Select-Object -Unique)
}

$names = Get-ContainerNames $unique
if ($names.Count -eq 0) {
  $names = Get-ContainerNames @("jamoa-jepsen", "jamoa-multidc")
}
$dumped = 0
foreach ($name in $names) {
  $safe = ($name -replace '[/:]', '__')
  $out = Join-Path $OutDir "$safe.log"
  Write-Host "  docker logs $name -> $out"
  docker logs --timestamps $name > $out 2>&1
  $volLog = Join-Path $OutDir "$safe-grid-visibility.log"
  docker cp "${name}:/app/data/grid-visibility.log" $volLog 2>$null
  if (Test-Path $volLog) { Write-Host "  volume log $name -> $volLog" }
  $dumped++
}

$createdOnly = 0
$psTxt = Join-Path $OutDir "docker-ps.txt"
if (Test-Path $psTxt) {
  $body = Get-Content $psTxt -Raw
  if ($body -match 'Created' -and $body -notmatch 'Up |healthy') { $createdOnly = 1 }
}
$summary = @(
  "dumped_containers=$dumped",
  "CLASS=$(if ($env:FAIL_CLASS) { $env:FAIL_CLASS } else { 'unknown' })",
  "HARNESS_REASON=$($env:HARNESS_REASON)",
  "stamp=$stamp",
  "containers_created_only=$createdOnly"
)
$summary | Tee-Object -FilePath (Join-Path $OutDir "SUMMARY.txt") | ForEach-Object { Write-Host $_ }
$safeDump = Get-JepsenSafeLogPath $OutDir
Write-Host "DUMP_DIR=$safeDump"
[IO.File]::WriteAllText((Join-Path $JEPSEN_DIR "cluster-logs\LATEST.txt"), $safeDump)
[IO.File]::WriteAllText((Join-Path $JEPSEN_DIR "CLUSTER_LOGS_DIR.txt"), $safeDump)
exit 0