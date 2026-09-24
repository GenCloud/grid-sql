# Full Clojure Jepsen against Compose N=3 (Windows PowerShell host).
# Always rebuilds the node image via host-jar (mvn on host + slim docker build).
# Root cause of prior PROTOCOL_ERROR: UTF-16 .dockerignore → empty/broken build context.
param(
  [int]$TimeLimit = 30,
  [switch]$SkipRebuild,
  # Fast path: skip image rebuild if present, shorter settle, default time-limit 30
  [switch]$Fast,
  [int]$SettleSec = 0
)
$ErrorActionPreference = "Continue"
if ($env:JEPSEN_TIME_LIMIT) {
  $TimeLimit = [int]$env:JEPSEN_TIME_LIMIT
}
if ($Fast) {
  $SkipRebuild = $true
  if (-not $env:JEPSEN_TIME_LIMIT) {
    $TimeLimit = 30
  }
  if ($SettleSec -le 0) {
    $SettleSec = 8
  }
}
if ($SettleSec -le 0) {
  $SettleSec = 12
}
$Script:DefaultSettleSec = $SettleSec
$JEPSEN_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ROOT = (Resolve-Path (Join-Path $JEPSEN_DIR "../..")).Path
Set-Location $JEPSEN_DIR

if (-not $env:HOME -or $env:HOME -eq "") {
  $env:HOME = $env:USERPROFILE
}
if (-not $env:JEPSEN_M2 -or $env:JEPSEN_M2 -eq "") {
  $env:JEPSEN_M2 = Join-Path $env:USERPROFILE ".m2"
}
$env:MSYS_NO_PATHCONV = "1"
$env:DOCKER_BUILDKIT = "1"

function Stamp-Outcome([string]$Outcome, [string]$Notes) {
  if (-not $env:STAMP -or $env:STAMP -eq "") {
    if ($env:JMH_STAMP -and $env:JMH_STAMP -ne "") {
      $env:STAMP = $env:JMH_STAMP
    } else {
      $env:STAMP = (Get-Date -Format "yyyy-MM-dd") + "-jepsen-full"
    }
  }
  $env:MODE = "full-jepsen"
  $env:OUTCOME = $Outcome
  $env:NOTES = $Notes
  $env:COMMAND = "run-jepsen.ps1 register+append (time-limit=$TimeLimit)"
  $env:FULL = $Outcome
  $env:COMPOSE_STATUS = "up"
  $env:CHAOS = "partition+kill"
  $stampPs1 = Join-Path $PSScriptRoot "stamp-results.ps1"
  if (Test-Path $stampPs1) {
    & $stampPs1
  }
}

function Rebuild-Image {
  if ($SkipRebuild) {
    Write-Host "SkipRebuild: using existing jamoa-grid-jepsen:local"
    return
  }
  Write-Host "Rebuilding Jepsen image (host-jar)..."
  & (Join-Path $PSScriptRoot "build-jepsen-image.ps1")
  if ($LASTEXITCODE -ne 0) { throw "build-jepsen-image failed: $LASTEXITCODE" }
}

function Ensure-Cluster {
  Write-Host "Ensuring Compose cluster (fresh volumes)..."
  $purge = Join-Path $PSScriptRoot "jepsen-purge.ps1"
  if (Test-Path $purge) {
    & $purge -Scope "1dc"
  } else {
    docker compose down -v --remove-orphans
  }
  Rebuild-Image
  Write-Host "Starting n1/n2/n3 from jamoa-grid-jepsen:local..."
  docker compose up -d --force-recreate n1 n2 n3
  if ($LASTEXITCODE -ne 0) {
    throw "compose up failed: $LASTEXITCODE"
  }
  Write-Host "Settling cluster SettleSec=$Script:DefaultSettleSec ..."
  # Wait for SQL ports instead of fixed long sleep
  $deadline = (Get-Date).AddSeconds([Math]::Max(30, $Script:DefaultSettleSec + 40))
  $ready = $false
  do {
    Start-Sleep -Seconds 2
    $ok = 0
    foreach ($port in @(15432, 15433, 15434)) {
      try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect("127.0.0.1", $port, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne(800) -and $c.Connected) { $ok++ }
        $c.Close()
      } catch { }
    }
    if ($ok -ge 3) {
      $ready = $true
      break
    }
  } while ((Get-Date) -lt $deadline)
  if (-not $ready) {
    Write-Host "WARN: not all SQL ports open after settle; continuing"
  }
}
function Ensure-Control {
  Write-Host "Starting Jepsen control (install lein if needed)..."
  docker compose --profile control up -d jepsen | Out-Null
  $deadline = (Get-Date).AddMinutes(8)
  $pollSec = 2
  do {
    Start-Sleep -Seconds $pollSec
    $out = docker compose --profile control exec -T jepsen bash -lc "test -f /tmp/jepsen-control-ready && command -v lein && lein version" 2>&1
    if ($LASTEXITCODE -eq 0 -and ("$out" -match "Leiningen")) {
      Write-Host $out
      return
    }
    Write-Host "control not ready yet (exit=$LASTEXITCODE)"
  } while ((Get-Date) -lt $deadline)
  throw "jepsen control not ready (lein / ready flag)"
}

function Install-SqlClient {
  if ($Fast -and (Test-Path (Join-Path $env:JEPSEN_M2 "repository\org\genfork\grid-sql-client\1.0-SNAPSHOT\grid-sql-client-1.0-SNAPSHOT.jar"))) {
    Write-Host "Fast: skip mvn install grid-sql-client (jar present in local m2)"
    return
  }
  Write-Host "Installing grid-sql-client to local Maven repo..."
  Push-Location $ROOT
  try {
    mvn -B -pl grid-sql-client -am install "-DskipTests"
    if ($LASTEXITCODE -ne 0) { throw "mvn install grid-sql-client failed" }
  } finally {
    Pop-Location
  }
}

function Run-Workload([string]$Workload) {
  Ensure-Control
  $scriptName = "run-workload-chaos-$Workload.sh"
  $hostScript = Join-Path $JEPSEN_DIR "scripts\$scriptName"
  $body = @"
#!/bin/bash
set +e
export PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export JAVA_HOME=/opt/java/openjdk
export JAVA_CMD=/opt/java/openjdk/bin/java
export JVM_OPTS="-Xmx2g -XX:+UseG1GC"
export LEIN_JVM_OPTS="-Xmx1g"
export JAVA_TOOL_OPTIONS="--enable-preview -Xmx2g"
cd /jepsen/jamoa
export JEPSEN_NODES=n1,n2,n3 JEPSEN_HTTP_PORTS=7777,7778,7779 JEPSEN_SQL_PORTS=15432,15433,15434
export JEPSEN_SCRIPTS=/jepsen/scripts JEPSEN_USE_LOCALHOST=0
command -v lein
command -v git
lein run -m jamoa-jepsen.core test --workload $Workload --time-limit $TimeLimit
echo LEIN_EXIT=`$?
"@
  $enc = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText($hostScript, ($body -replace "`r`n", "`n"), $enc)
  $out = docker compose --profile control exec -T jepsen bash /jepsen/scripts/$scriptName 2>&1
  $out | ForEach-Object { Write-Host $_ }
  $text = ($out | Out-String)
  if ($text -match "Analysis invalid") {
    return 1
  }
  if ($text -match "Everything looks good") {
    return 0
  }
  # Prefer final Elle/Knossos summary line over nested timeline :valid? true
  if ($text -match "(?m)^ :valid\? true") {
    return 0
  }
  $m = [regex]::Match($text, "LEIN_EXIT=(\d+)")
  if ($m.Success) { return [int]$m.Groups[1].Value }
  $code = $LASTEXITCODE
  Write-Host "WARN: workload $Workload exit=$code"
  return $(if ($null -eq $code) { 1 } else { $code })
}

Install-SqlClient
Ensure-Cluster
Write-Host "=== Jepsen workload: register (Knossos) ==="
$regCode = Run-Workload register
$REGISTER_OUTCOME = if ($regCode -eq 0) { "PASS" } else { "FAIL" }

Ensure-Cluster
Write-Host "=== Jepsen workload: append (Elle) ==="
$appCode = Run-Workload append
$APPEND_OUTCOME = if ($appCode -eq 0) { "PASS" } else { "FAIL" }

$NOTES = "register=$REGISTER_OUTCOME; append=$APPEND_OUTCOME"
if ($REGISTER_OUTCOME -eq "PASS" -and $APPEND_OUTCOME -eq "PASS") {
  Stamp-Outcome "PASS" $NOTES
  exit 0
}
Stamp-Outcome "FAIL" $NOTES
exit 1