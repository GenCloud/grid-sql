$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$env:JMH_STAMP = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { Get-Date -Format "yyyy-MM-dd" }
Set-Location $Root
$Settings = Join-Path $Root "benchmarks/compare/maven-central-settings.xml"
mvn -s $Settings -pl grid-server-core -Pjmh "-Dtest=DuplexCodecBenchmark" "-Dsurefire.failIfNoSpecifiedTests=false" test
