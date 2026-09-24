# No-nemesis latency baseline (algorithm gate). Consistency checkers still run.
# Always rebuilds via host-jar (build-jepsen-image.ps1).
param(
  [int]$TimeLimit = 30
)
$ErrorActionPreference = "Continue"
if ($env:JEPSEN_TIME_LIMIT) {
  $TimeLimit = [int]$env:JEPSEN_TIME_LIMIT
}
$JEPSEN_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $JEPSEN_DIR

# Windows Docker Compose: HOME is often unset вЂ” point m2 at USERPROFILE.
if (-not $env:HOME -or $env:HOME -eq "") {
  $env:HOME = $env:USERPROFILE
}
if (-not $env:JEPSEN_M2 -or $env:JEPSEN_M2 -eq "") {
  $env:JEPSEN_M2 = Join-Path $env:USERPROFILE ".m2"
}
$env:MSYS_NO_PATHCONV = "1"

function Ensure-Cluster {
  Write-Host "Ensuring Compose cluster (fresh volumes)..."
  docker compose down -v --remove-orphans
  if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne $null) {
    Write-Host "compose down exit=$LASTEXITCODE (ignored)"
  }
  Write-Host "Rebuilding Jepsen image (host-jar)..."
  $env:DOCKER_BUILDKIT = "1"
  & (Join-Path $PSScriptRoot "build-jepsen-image.ps1")
  if ($LASTEXITCODE -ne 0) { throw "build-jepsen-image failed: $LASTEXITCODE" }
  Write-Host "Starting n1/n2/n3 from jamoa-grid-jepsen:local..."
  docker compose up -d --force-recreate n1 n2 n3
  if ($LASTEXITCODE -ne 0) {
    throw "compose up failed: $LASTEXITCODE"
  }
  Write-Host "Waiting for n1/n2/n3 healthy..."
  $deadline = (Get-Date).AddMinutes(3)
  do {
    Start-Sleep -Seconds 5
    $ok = $true
    foreach ($name in @("jamoa-jepsen-n1", "jamoa-jepsen-n2", "jamoa-jepsen-n3")) {
      $st = docker inspect -f "{{.State.Health.Status}}" $name 2>$null
      if ($st -ne "healthy") { $ok = $false }
    }
  } while (-not $ok -and (Get-Date) -lt $deadline)
  if (-not $ok) {
    Write-Host "WARN: cluster not fully healthy yet; continuing"
  }
  # Plan E: also require SQL TCP listen (acceptance path), not /jepsen/register HTTP.
  foreach ($port in @(15432, 15433, 15434)) {
    $tcpOk = $false
    try {
      $c = New-Object System.Net.Sockets.TcpClient
      $iar = $c.BeginConnect("127.0.0.1", $port, $null, $null)
      $tcpOk = $iar.AsyncWaitHandle.WaitOne(1500) -and $c.Connected
      $c.Close()
    } catch { $tcpOk = $false }
    if (-not $tcpOk) {
      Write-Host "WARN: SQL port $port not open yet (grid.sql-server); continuing"
    }
  }
}

function Ensure-Control {
  docker compose --profile control up -d jepsen | Out-Null
  Write-Host "Waiting for control lein ready..."
  $deadline = (Get-Date).AddMinutes(8)
  do {
    Start-Sleep -Seconds 5
    $out = docker compose --profile control exec -T jepsen bash -lc "test -f /tmp/jepsen-control-ready && command -v lein && lein version" 2>&1
    if ($LASTEXITCODE -eq 0 -and ("$out" -match "Leiningen")) {
      Write-Host $out
      return
    }
    Write-Host "control not ready yet (exit=$LASTEXITCODE)"
  } while ((Get-Date) -lt $deadline)
  throw "jepsen control not ready (lein / ready flag)"
}

function Run-Workload([string]$Workload) {
  Ensure-Control
  $scriptName = "run-workload-$Workload.sh"
  $hostScript = Join-Path $JEPSEN_DIR "scripts\$scriptName"
  $body = @"
#!/bin/bash
set -e
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
lein run -m jamoa-jepsen.core test --workload $Workload --time-limit $TimeLimit --no-nemesis
"@
  $utf8 = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText($hostScript, ($body -replace "`r`n", "`n"), $utf8)
  docker compose --profile control exec -T jepsen bash /jepsen/scripts/$scriptName
  $code = $LASTEXITCODE
  if ($code -ne 0) {
    Write-Host "WARN: workload $Workload exit=$code (history may still be valid for qg-gate)"
    if ($code -gt 2) {
      throw "Workload $Workload failed with exit $code"
    }
  }
}

Write-Host "Installing grid-sql-client into $($env:JEPSEN_M2) ..."
$root = (Resolve-Path (Join-Path $JEPSEN_DIR "../..")).Path
Push-Location $root
try {
  mvn -B -pl grid-sql-client -am install "-DskipTests"
  if ($LASTEXITCODE -ne 0) { throw "mvn install grid-sql-client failed" }
} finally {
  Pop-Location
}

Ensure-Cluster
Write-Host "=== no-chaos register ==="
Run-Workload register
Ensure-Cluster
Write-Host "=== no-chaos append ==="
Run-Workload append
Write-Host "no-chaos done; parse latency with scripts/latency-from-history.ps1 on store/*/history.edn"
