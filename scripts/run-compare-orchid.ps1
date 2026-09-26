$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$env:JMH_STAMP = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { Get-Date -Format "yyyy-MM-dd" }
Set-Location $Root
$Settings = Join-Path $Root "benchmarks/compare/maven-central-settings.xml"
Write-Host "=== ORCHID durable compare (JMH fsync-on paths) ==="
mvn -s $Settings -pl grid-server-core -Pjmh "-Dtest=OrchidDurableCompareBenchmark,TwoNodeOrchidCommitBenchmark" "-Dsurefire.failIfNoSpecifiedTests=false" test
Write-Host "JSON under grid-server-core/benchmarks/lab/"
