param(
  [switch]$Fast
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Module = Join-Path $Root "grid-server-core"
$Results = Join-Path $Module "benchmarks\lab"
New-Item -ItemType Directory -Force -Path $Results | Out-Null

if ($Fast) {
  $env:JMH_FAST = "1"
  Write-Host "JMH latency -Fast: shorter iters, sequential only (not a QG stamp)"
}

$Stamp = Get-Date -Format "yyyy-MM-dd"
if ($env:JMH_STAMP) { $Stamp = $env:JMH_STAMP }

$Benches = @(
  "OrchidCommitLatencyBenchmark",
  "TwoNodeOrchidCommitBenchmark",
  "OpLogAppendBenchmark",
  "DuplexCodecBenchmark",
  "ReplicaReadLatencyBenchmark"
)
if ($env:JMH_INCLUDE_LAX -eq "1") {
  $Benches += "LaxGridCompositeIndexBenchmarkTest"
}

$Fingerprint = Join-Path $Results "RESULTS.md"
$Os = [System.Environment]::OSVersion.VersionString
$Cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name
$Java = (& cmd /c "java -version 2>&1" | Out-String).Trim()

$IterNote = if ($env:JMH_FAST -eq "1") { "warmup 1x1s, measure 1x1s (JMH_FAST)" } else { "warmup 2x1s, measure 3x1s" }

$md = @"
# JMH latency results

## Machine fingerprint

- Stamp: $Stamp
- OS: $Os
- CPU: $Cpu
- Java:
``````
$Java
``````

## Suite

Latency harness: ``AbstractLatencyBenchmark`` (forks=1, threads=1, $IterNote).
Tracks run **sequentially** — never parallelize on one host.

"@
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($Fingerprint, $md, $utf8)

$env:JMH_STAMP = $Stamp
Set-Location $Root
foreach ($b in $Benches) {
  Write-Host "=== JMH $b (sequential) ==="
  $extra = @()
  if ($env:JMH_FAST -eq "1") { $extra += "-Djmh.fast=true" }
  & mvn -pl grid-server-core -Pjmh "-Dtest=$b" "-Dsurefire.failIfNoSpecifiedTests=false" @extra test
  if ($LASTEXITCODE -ne 0) {
    throw "JMH failed for $b"
  }
}

Write-Host "Append ReplicationMetrics / notes to $Fingerprint after run."
Write-Host "JSON under $Results (*-latency-*.json)"
Get-ChildItem $Results -Filter "$Stamp-latency-*.json" | ForEach-Object { Write-Host " - $($_.Name)" }