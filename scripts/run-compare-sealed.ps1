$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$env:JMH_STAMP = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { Get-Date -Format "yyyy-MM-dd" }
# Full matrix required for QG: 1k / 100k / 1M. Fast smoke: SEALED_BENCH_ROWS=1000 (RAN only).
$Rows = "1000,100000,1000000"
if ($env:SEALED_BENCH_ROWS) { $Rows = $env:SEALED_BENCH_ROWS }
Set-Location $Root

$mvnArgs = @(
  "-pl", "grid-server-core", "-Pjmh",
  "-Dtest=SealedQueryPathBenchmark,SealedShardPackBenchmark",
  "-Dsealed.bench.rows=$Rows",
  "-Dsealed.bench.modes=memory,disk,hybrid",
  "-Dsurefire.forkedProcessTimeoutInSeconds=3600",
  "-Dsurefire.failIfNoSpecifiedTests=false"
)
if ($env:JMH_FAST -eq "1") {
  $mvnArgs += "-Djmh.fast=true"
  Write-Host "Sealed JMH_FAST rows=$Rows (smoke - not hard QG sealed gate)"
}

$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& mvn @mvnArgs test
$mvnExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($null -ne $mvnExit -and $mvnExit -ne 0) {
  throw "Sealed JMH (QueryPath+ShardPack) failed: $mvnExit"
}
