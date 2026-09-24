$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Compare = Join-Path $Root "benchmarks\compare"
$Starter = Join-Path $Root "grid-server-core"
$Results = Join-Path $Starter "benchmarks\lab"
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$Stamp = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { Get-Date -Format "yyyy-MM-dd" }
$Ops = if ($env:COMPARE_OPS) { $env:COMPARE_OPS } else { "200" }

Set-Location $Compare
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker compose up -d redis redis-replica | Out-Null
$composeExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($null -ne $composeExit -and $composeExit -ne 0) {
  throw "docker compose up redis failed: $composeExit"
}
Start-Sleep -Seconds 3

$env:JMH_STAMP = $Stamp
$env:COMPARE_OPS = "$Ops"
$env:REDIS_HOST = "127.0.0.1"
$env:REDIS_PORT = "6379"
$env:REDIS_REPLICA_HOST = "127.0.0.1"
$env:REDIS_REPLICA_PORT = "6380"
$env:COMPARE_RESULTS_DIR = $Results

Set-Location $Root
# -Pjmh: compile index/benchmarks (default Surefire excludes them); *Harness included in profile
mvn -q -pl grid-server-core -Pjmh "-Dtest=RedisJedisCompareHarness" "-Dsurefire.failIfNoSpecifiedTests=false" test
Write-Host "Redis compare via host Jedis complete (stamp=$Stamp)"