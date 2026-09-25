param(
  [int]$TimeLimit = 60,
  [switch]$SkipRebuild,
  [switch]$Fast,
  [switch]$NoNemesis
)
<#
.SYNOPSIS
  Multi-DC + Witness (w1) Jepsen chaos stamp for TD-HA-001.

.DESCRIPTION
  Overlay compose adds Hold+Hold+Witness voter. Uses Actuator /health/liveness.
  Honest PASS/FAIL only in benchmarks/jepsen/witness/RESULTS.md — never invent :valid? true.
#>
$ErrorActionPreference = "Continue"
if ($Fast) {
  $SkipRebuild = $true
  if (-not $PSBoundParameters.ContainsKey("TimeLimit") -and -not $env:JEPSEN_TIME_LIMIT) {
    $TimeLimit = 30
  }
}
if ($env:JEPSEN_TIME_LIMIT) {
  $TimeLimit = [int]$env:JEPSEN_TIME_LIMIT
}
$WITNESS_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$MULTIDC_DIR = (Resolve-Path (Join-Path $WITNESS_DIR "../multidc")).Path
$JEPSEN_DIR = (Resolve-Path (Join-Path $WITNESS_DIR "..")).Path
$ROOT = (Resolve-Path (Join-Path $JEPSEN_DIR "../..")).Path
$ResultsPath = Join-Path $WITNESS_DIR "RESULTS.md"
$Overlay = Join-Path $WITNESS_DIR "docker-compose.witness-overlay.yml"
Set-Location $MULTIDC_DIR
# Linux GHA / pwsh: USERPROFILE is often unset; prefer HOME then UserProfile folder.
if (-not $env:HOME -or $env:HOME -eq "") {
  if ($env:USERPROFILE) { $env:HOME = $env:USERPROFILE }
  else { $env:HOME = [Environment]::GetFolderPath("UserProfile") }
}
if (-not $env:JEPSEN_M2 -or $env:JEPSEN_M2 -eq "") {
  $env:JEPSEN_M2 = Join-Path $env:HOME ".m2"
}
$env:MULTIDC_MODE = "async"
$env:JEPSEN_WITNESS = "1"
$env:MSYS_NO_PATHCONV = "1"
$env:DOCKER_BUILDKIT = "1"
$GridUrl = "grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public"
$Utf8 = New-Object System.Text.UTF8Encoding $false
$ControlName = "jamoa-multidc-control"
function Write-Utf8File([string]$Path, [string]$Content) { [System.IO.File]::WriteAllText($Path, $Content, $Utf8) }
function Stamp-Witness {
  param([string]$Outcome,[string]$Notes,[string]$Reg,[string]$App,[string]$Lat)
  try { $Git = (git -C $ROOT rev-parse --short HEAD 2>$null) } catch { $Git = "unknown" }
  if (-not $Git) { $Git = "unknown" }
  $Date = Get-Date -Format "o"
  $Stamp = "2026-09-21-witness-chaos"
  $ChaosNote = if ($NoNemesis) { "no-nemesis" } else { "dc-link+kill-dc-a+revive (Witness overlay)" }
  $histLine = "| ``$Stamp`` | $Reg | $App | $Outcome | $Notes |"
  $priorHist = New-Object System.Collections.Generic.List[string]
  if (Test-Path $ResultsPath) {
    $prev = [System.IO.File]::ReadAllText($ResultsPath, $Utf8)
    foreach ($line in ($prev -split "`n")) {
      $t = $line.TrimEnd([char]13)
      if ($t.StartsWith("| ``") -and $t.Contains("|")) {
        if ($t.Contains($Stamp)) { continue }
        if ($t.Contains("pending-witness")) { continue }
        [void]$priorHist.Add($t)
      }
    }
  }
  [void]$priorHist.Add($histLine)
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.AppendLine("# Witness Jepsen RESULTS")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("Honest PASS/FAIL after Docker+lein Witness overlay (never invent ``:valid? true``).")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## Latest stamp")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("| Field | Value |")
  [void]$sb.AppendLine("|-------|--------|")
  [void]$sb.AppendLine("| stamp | ``$Stamp`` |")
  [void]$sb.AppendLine("| date | $Date |")
  [void]$sb.AppendLine("| git | $Git |")
  [void]$sb.AppendLine("| host | $($env:COMPUTERNAME) |")
  [void]$sb.AppendLine("| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |")
  [void]$sb.AppendLine("| outcome | ``$Outcome`` |")
  [void]$sb.AppendLine("| register | $Reg |")
  [void]$sb.AppendLine("| append | $App |")
  [void]$sb.AppendLine("| chaos | $ChaosNote |")
  [void]$sb.AppendLine("| multi-host SQL | ``$GridUrl`` |")
  [void]$sb.AppendLine("| health | ``/health/liveness`` (Actuator base-path ``/``) |")
  [void]$sb.AppendLine("| latency (ok-ops, warmup 10s) | $Lat |")
  [void]$sb.AppendLine("| notes | $Notes |")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## History")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("| stamp | register | append | outcome | notes |")
  [void]$sb.AppendLine("|-------|----------|--------|---------|-------|")
  foreach ($h in $priorHist) { [void]$sb.AppendLine($h) }
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Debt: TD-HA-001.")
  Write-Utf8File $ResultsPath $sb.ToString()
  Write-Host "Wrote $ResultsPath stamp=$Stamp outcome=$Outcome"
}
Write-Host "=== Witness chaos (TD-HA-001) ==="
Write-Host "SQL URL: $GridUrl"
docker compose -f docker-compose.yml -f $Overlay config | Out-Null
if ($LASTEXITCODE -ne 0) { throw "docker compose config failed: $LASTEXITCODE" }
Write-Host "compose config OK (Witness overlay)"
docker info 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Stamp-Witness -Outcome "FAIL" -Notes "BLOCKED: docker daemon unavailable" -Reg "-" -App "-" -Lat "n/a"; exit 2 }
function Ensure-Image {
  docker image inspect jamoa-grid-jepsen:local 2>$null | Out-Null
  $haveImage = ($LASTEXITCODE -eq 0)
  if (-not $haveImage -and -not $SkipRebuild) {
    Write-Host "Building Jepsen image (host-jar)..."
    $env:JAVA_HOME = "C:\Users\MrCloud\.jdks\temurin-25"
    $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
    & (Join-Path $JEPSEN_DIR "scripts\build-jepsen-image.ps1")
    if ($LASTEXITCODE -ne 0) { throw "build-jepsen-image failed: $LASTEXITCODE" }
  }
  Write-Host "Overlaying multidc+witness configs onto jamoa-grid-jepsen:local..."
  Push-Location $ROOT
  try {
    docker build -f benchmarks/jepsen/Dockerfile.witness-overlay -t jamoa-grid-jepsen:local .
    if ($LASTEXITCODE -ne 0) { throw "witness overlay docker build failed: $LASTEXITCODE" }
  } finally { Pop-Location }
}
function Ensure-SqlClient {
  Write-Host "Installing grid-sql-client..."
  Push-Location $ROOT
  try { mvn -B -pl grid-sql-client -am install "-DskipTests"; if ($LASTEXITCODE -ne 0) { throw "mvn install failed" } } finally { Pop-Location }
}
function Ensure-Cluster {
  Write-Host "Starting Multi-DC + Witness cluster..."
  Ensure-Image
  Write-Host "Fresh cluster: purge multidc (+ witness) data volumes..."
  $purge = Join-Path $JEPSEN_DIR "scripts\jepsen-purge.ps1"
  if (Test-Path $purge) {
    & $purge -Scope "multidc"
  }
  docker compose -f docker-compose.yml -f $Overlay stop a1 a2 a3 b1 b2 w1 2>$null
  docker compose -f docker-compose.yml -f $Overlay rm -f a1 a2 a3 b1 b2 w1 2>$null
  foreach ($v in @("multidc_a1-data","multidc_a2-data","multidc_a3-data","multidc_b1-data","multidc_b2-data","multidc_w1-data","w1-data")) {
    docker volume rm -f $v 2>$null
  }
  docker compose -f docker-compose.yml -f $Overlay up -d --force-recreate a1 a2 a3 b1 b2 w1
  if ($LASTEXITCODE -ne 0) { throw "compose up failed: $LASTEXITCODE" }
  Write-Host "Waiting for health (up to 6 min)..."
  $deadline = (Get-Date).AddMinutes(6)
  $ok = $false
  do {
    Start-Sleep -Seconds 8
    $ok = $true
    foreach ($name in @("jamoa-multidc-a1","jamoa-multidc-a2","jamoa-multidc-a3","jamoa-multidc-b1","jamoa-multidc-b2","jamoa-multidc-w1")) {
      $st = docker inspect -f "{{.State.Health.Status}}" $name 2>$null
      if ($st -ne "healthy") { $ok = $false; Write-Host "  $name=$st" }
    }
  } while (-not $ok -and (Get-Date) -lt $deadline)
  if (-not $ok) { Write-Host "WARN: not fully healthy; continuing" }
}
function Sync-ControlWorkspace {
  Write-Host "docker cp clojure + scripts + grid-sql-client into control..."
  docker exec $ControlName bash -lc "mkdir -p /jepsen/jamoa/store /jepsen/scripts /root/.m2/repository/org/genfork; rm -rf /jepsen/jamoa/src /jepsen/jamoa/project.clj /jepsen/scripts/*"
  docker cp (Join-Path $JEPSEN_DIR "clojure\project.clj") "${ControlName}:/jepsen/jamoa/project.clj"
  docker cp (Join-Path $JEPSEN_DIR "clojure\src") "${ControlName}:/jepsen/jamoa/src"
  docker cp (Join-Path $JEPSEN_DIR "scripts\.") "${ControlName}:/jepsen/scripts/"
  $m2Genfork = Join-Path $env:JEPSEN_M2 "repository/org/genfork"
  if (-not (Test-Path (Join-Path $m2Genfork "grid-sql-client"))) { throw "host m2 missing org.genfork/grid-sql-client" }
  docker exec $ControlName bash -lc "rm -rf /root/.m2/repository/org/genfork"; docker cp $m2Genfork "${ControlName}:/root/.m2/repository/org/genfork"
}
function Ensure-Control {
  Write-Host "Starting Jepsen control..."
  docker compose --profile control up -d jepsen | Out-Null
  $deadline = (Get-Date).AddMinutes(12)
  do {
    Start-Sleep -Seconds 5
    $out = docker compose --profile control exec -T jepsen bash -lc "test -f /tmp/jepsen-control-ready; command -v lein; lein version" 2>&1
    if ($LASTEXITCODE -eq 0 -and ("$out" -match "Leiningen")) { Write-Host $out; Sync-ControlWorkspace; return }
    Write-Host "control not ready yet (exit=$LASTEXITCODE)"
  } while ((Get-Date) -lt $deadline)
  throw "jepsen control not ready"
}
function Fetch-Store {
  $hostStore = Join-Path $JEPSEN_DIR "clojure\store"
  New-Item -ItemType Directory -Force -Path $hostStore | Out-Null
  docker exec $ControlName bash -lc "cd /jepsen/jamoa && tar --exclude=current -cf /tmp/jepsen-store.tar store 2>/dev/null" | Out-Null
  docker cp "${ControlName}:/tmp/jepsen-store.tar" (Join-Path $hostStore "jepsen-store.tar") 2>$null
  $tar = Join-Path $hostStore "jepsen-store.tar"
  if (Test-Path $tar) {
    Push-Location $hostStore
    try {
      tar -xf jepsen-store.tar 2>$null
      if (Test-Path "store") {
        Get-ChildItem store | ForEach-Object {
          $dest = Join-Path (Get-Location) $_.Name
          if (Test-Path $dest) { Remove-Item -Recurse -Force $dest -ErrorAction SilentlyContinue }
          Move-Item -LiteralPath $_.FullName -Destination . -Force
        }
        Remove-Item -Recurse -Force store -ErrorAction SilentlyContinue
      }
    } finally { Pop-Location }
    Remove-Item -Force $tar -ErrorAction SilentlyContinue
  }
}
function Run-Workload([string]$Workload) {
  Ensure-Control
  $nemArg = ""
  if ($NoNemesis) { $nemArg = "1" }
  $out = docker compose --profile control exec -T -e "MULTIDC_MODE=async" -e "JEPSEN_WITNESS=1" jepsen bash /jepsen/scripts/run-workload-multidc.sh $Workload $TimeLimit $nemArg 2>&1
  $out | ForEach-Object { Write-Host $_ }
  Fetch-Store
  $text = ($out | Out-String)
  $valid = ($text -match "Everything looks good") -or ($text -match ":valid\? true")
  $failClaim = ($text -match ":valid\? false") -or ($text -match "Analysis invalid")
  if ($valid -and -not $failClaim) { return @{ code = 0; text = $text } }
  $m = [regex]::Match($text, "LEIN_EXIT=(\d+)")
  $code = if ($m.Success) { [int]$m.Groups[1].Value } elseif ($null -eq $LASTEXITCODE) { 1 } else { [int]$LASTEXITCODE }
  if ($code -eq 0 -and $failClaim) { $code = 1 }
  return @{ code = $code; text = $text }
}
function Latest-StoreDir([string]$Workload) {
  $base = Join-Path $JEPSEN_DIR "clojure\store"
  foreach ($dir in @((Join-Path $base "jamoa-orchid-$Workload"), (Join-Path $base "store\jamoa-orchid-$Workload"))) {
    if (Test-Path $dir) { return (Get-ChildItem $dir -Directory | Sort-Object Name -Descending | Select-Object -First 1) }
  }
  return $null
}
function Latency-Line([string]$Workload) {
  $store = Latest-StoreDir $Workload
  if (-not $store) { return "$Workload`: no store" }
  $hist = Join-Path $store.FullName "history.edn"
  if (-not (Test-Path $hist)) { return "$Workload`: no history.edn" }
  $latScript = Join-Path $JEPSEN_DIR "scripts\latency-from-history.ps1"
  $lines = & $latScript -HistoryEdn $hist -Workload $Workload -WarmupSeconds 10 2>&1
  return (($lines | Out-String).Trim() -replace "`r?`n", " | ")
}
try {
  Ensure-SqlClient
  Ensure-Cluster
  Write-Host "=== Witness workload: register ==="
  $reg = Run-Workload register
  $REGISTER_OUTCOME = if ($reg.code -eq 0) { "PASS (:valid? true)" } else { "FAIL" }
  Ensure-Cluster
  Write-Host "=== Witness workload: append ==="
  $app = Run-Workload append
  $APPEND_OUTCOME = if ($app.code -eq 0) { "PASS (:valid? true)" } else { "FAIL" }
  $Lat = "register: $(Latency-Line register); append: $(Latency-Line append)"
  $NOTES = "register=$REGISTER_OUTCOME; append=$APPEND_OUTCOME; time-limit=$TimeLimit; Witness w1 overlay"
  $Outcome = if ($reg.code -eq 0 -and $app.code -eq 0) { "PASS" } else { "FAIL" }
  Stamp-Witness -Outcome $Outcome -Notes $NOTES -Reg $REGISTER_OUTCOME -App $APPEND_OUTCOME -Lat $Lat
  if ($Outcome -eq "PASS") { exit 0 }
  exit 1
} catch {
  $msg = $_.Exception.Message
  Write-Host "ERROR: $msg"
  Stamp-Witness -Outcome "FAIL" -Notes ("BLOCKED/ERROR: " + $msg) -Reg "-" -App "-" -Lat "n/a"
  exit 2
}
