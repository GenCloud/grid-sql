$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$env:JMH_STAMP = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { Get-Date -Format "yyyy-MM-dd" }
Set-Location $Root
mvn -pl grid-server-core "-Dtest=WalCompareBenchmark" test
