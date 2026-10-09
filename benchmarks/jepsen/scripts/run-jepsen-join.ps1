param(
  [int]$TimeLimit = 60,
  [switch]$SkipRebuild,
  [switch]$Fast,
  [int]$SettleSec = 0
)
$ErrorActionPreference = "Continue"
if ($env:JEPSEN_TIME_LIMIT) { $TimeLimit = [int]$env:JEPSEN_TIME_LIMIT }
if ($Fast) {
  $SkipRebuild = $true
  if (-not $env:JEPSEN_TIME_LIMIT) { $TimeLimit = 30 }
  if ($SettleSec -le 0) { $SettleSec = 8 }
}
if ($SettleSec -le 0) { $SettleSec = 12 }
$JEPSEN_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ROOT = (Resolve-Path (Join-Path $JEPSEN_DIR "../..")).Path
Set-Location $JEPSEN_DIR
if (-not $env:HOME -or $env:HOME -eq "") { $env:HOME = $env:USERPROFILE }
if (-not $env:JEPSEN_M2 -or $env:JEPSEN_M2 -eq "") { $env:JEPSEN_M2 = Join-Path $env:USERPROFILE ".m2" }
$env:MSYS_NO_PATHCONV = "1"
$env:DOCKER_BUILDKIT = "1"
$env:JEPSEN_JOIN_SHARDS = "1"
$env:JEPSEN_SWARM = ""
$env:JEPSEN_UNCLEAN_REVIVE = ""
$env:JEPSEN_MULTIDC = ""

function Stamp-Outcome([string]$Outcome, [string]$Notes) {
  if (-not $env:STAMP -or $env:STAMP -eq "") {
    $env:STAMP = (Get-Date -Format "yyyy-MM-dd") + "-jepsen-join-shards"
  }
  $env:MODE = "1dc-join-shards-chaos"
  $env:OUTCOME = $Outcome
  $env:NOTES = $Notes
  $env:COMMAND = "run-jepsen-join.ps1 join (time-limit=$TimeLimit)"
  $env:FULL = $Outcome
  $env:COMPOSE_STATUS = "up"
  $env:CHAOS = "partition+kill"
  & (Join-Path $PSScriptRoot "stamp-results.ps1")
}

function Ensure-Cluster {
  & (Join-Path $PSScriptRoot "jepsen-purge.ps1") -Scope "1dc"
  if (-not $SkipRebuild) {
    & (Join-Path $PSScriptRoot "build-jepsen-image.ps1")
    if ($LASTEXITCODE -ne 0) { throw "build-jepsen-image failed" }
  }
  docker compose up -d --force-recreate n1 n2 n3
  if ($LASTEXITCODE -ne 0) { throw "compose up failed" }
  Start-Sleep -Seconds $SettleSec
}

function Ensure-Control {
  docker compose --profile control up -d jepsen | Out-Null
  $deadline = (Get-Date).AddMinutes(8)
  do {
    Start-Sleep -Seconds 2
    $out = docker compose --profile control exec -T jepsen bash -lc "test -f /tmp/jepsen-control-ready && command -v lein && lein version" 2>&1
    if ($LASTEXITCODE -eq 0 -and ("$out" -match "Leiningen")) { return }
  } while ((Get-Date) -lt $deadline)
  throw "jepsen control not ready"
}

function Run-Join {
  Ensure-Control
  $scriptName = "run-workload-chaos-join.sh"
  $body = @"
#!/bin/bash
set +e
export PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export JAVA_HOME=/opt/java/openjdk JAVA_CMD=/opt/java/openjdk/bin/java
export JVM_OPTS="-Xmx2g -XX:+UseG1GC" LEIN_JVM_OPTS="-Xmx1g"
export JAVA_TOOL_OPTIONS="--enable-preview -Xmx2g"
cd /jepsen/jamoa
export JEPSEN_NODES=n1,n2,n3 JEPSEN_HTTP_PORTS=7777,7778,7779 JEPSEN_SQL_PORTS=15432,15433,15434
export JEPSEN_SCRIPTS=/jepsen/scripts JEPSEN_USE_LOCALHOST=0
export JEPSEN_JOIN_SHARDS=1
pkill -9 -f 'jamoa-jepsen.core' 2>/dev/null || true
sleep 1
lein run -m jamoa-jepsen.core test --workload join --time-limit $TimeLimit
echo LEIN_EXIT=`$?
"@
  $enc = New-Object System.Text.UTF8Encoding $false
  [IO.File]::WriteAllText((Join-Path $JEPSEN_DIR "scripts\$scriptName"), ($body -replace "`r`n","`n"), $enc)
  $out = docker compose --profile control exec -T jepsen bash /jepsen/scripts/$scriptName 2>&1
  $out | ForEach-Object { Write-Host $_ }
  $text = ($out | Out-String)
  if ($text -match "Analysis invalid") { return 1 }
  if ($text -match "Everything looks good") { return 0 }
  if ($text -match "(?m)^ :valid\? true") { return 0 }
  $m = [regex]::Match($text, "LEIN_EXIT=(\d+)")
  if ($m.Success) { return [int]$m.Groups[1].Value }
  return 1
}

$script:ExitCode = 1
try {
  . (Join-Path $PSScriptRoot "jepsen-env.ps1")
  Initialize-JepsenHostEnv
  [void](Sync-JepsenProjectClj -RepoRoot $ROOT)
  Push-Location $ROOT
  mvn -B -pl grid-sql-client,grid-sql-jepsen-starter -am install "-DskipTests"
  if ($LASTEXITCODE -ne 0) { throw "mvn install failed" }
  Pop-Location
  Ensure-Cluster
  Write-Host "=== Jepsen JOIN/shards: Elle via cross-shard JOIN read ==="
  $code = Run-Join
  $outc = if ($code -eq 0) { "PASS" } else { "FAIL" }
  Stamp-Outcome $outc "join-shards=$outc"
  $script:ExitCode = $code
} finally {
  Remove-Item Env:JEPSEN_JOIN_SHARDS -ErrorAction SilentlyContinue
  & (Join-Path $PSScriptRoot "jepsen-purge.ps1") -Scope "1dc"
}
exit $script:ExitCode