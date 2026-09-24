$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$env:JMH_STAMP = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { Get-Date -Format "yyyy-MM-dd" }
Set-Location $Root
Write-Host "=== ORCHID durable compare (JMH fsync-on paths) ==="
mvn -pl grid-server-core "-Dtest=OrchidDurableCompareBenchmark,TwoNodeOrchidCommitBenchmark" test
Write-Host "JSON under grid-server-core/benchmarks/lab/"
