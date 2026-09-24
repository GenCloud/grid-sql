# TLC Orchid hard step is NOT in this script. Use scripts/run-perf-gate.ps1 (calls run-tlc-orchid) before or after compare.
# Tracks always run SEQUENTIALLY on one host - never parallelize JMH/OSS peers (distorts every result).
param(
  # Smoke path: shorter JMH iters, sealed rows=1000, skip slow peer tracks. NOT a QG stamp.
  [switch]$Fast
)
$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Settings = Join-Path $Root "benchmarks/compare/maven-central-settings.xml"
if ($Fast) {
  if (-not $env:JMH_STAMP -or $env:JMH_STAMP -eq "") {
    $env:JMH_STAMP = (Get-Date -Format "yyyy-MM-dd") + "-compare-fast"
  }
  $env:JMH_FAST = "1"
  if (-not $env:SEALED_BENCH_ROWS -or $env:SEALED_BENCH_ROWS -eq "") {
    $env:SEALED_BENCH_ROWS = "1000"
  }
  Write-Host "=== JMH -Fast: sequential smoke (NOT a QG / SUMMARY stamp) ==="
  Write-Host "JMH_FAST=1 SEALED_BENCH_ROWS=$($env:SEALED_BENCH_ROWS) - skip etcd/redis/hazelcast/multi-dc/sql-* peers"
} else {
  if (-not $env:JMH_STAMP -or $env:JMH_STAMP -eq "") {
    $env:JMH_STAMP = (Get-Date -Format "yyyy-MM-dd") + "-compare"
  }
}
$Stamp = $env:JMH_STAMP
$Results = Join-Path $Root "grid-server-core/benchmarks/lab"
New-Item -ItemType Directory -Force -Path $Results | Out-Null

Write-Host "=== run-compare-all stamp=$Stamp Fast=$Fast ==="
Set-Location $Root

$failed = @()

function Invoke-Track([string]$Name, [scriptblock]$Body) {
  Write-Host ""
  Write-Host ">>> $Name (sequential)"
  Set-Location $Root
  try {
    & $Body
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
      $script:failed += $Name
      Write-Host "FAIL $Name exit=$LASTEXITCODE"
    } else {
      Write-Host "OK $Name"
    }
  } catch {
    $script:failed += $Name
    Write-Host "FAIL $Name : $_"
  }
}

function Invoke-MvnJmh([string]$Tests) {
  $extra = @()
  if ($env:JMH_FAST -eq "1") {
    $extra += "-Djmh.fast=true"
  }
  & mvn -s $Settings -pl grid-server-core -Pjmh "-Dtest=$Tests" "-Dsurefire.failIfNoSpecifiedTests=false" @extra test
}

if (-not $Fast) {
  Invoke-Track "etcd" {
    & (Join-Path $PSScriptRoot "run-compare-etcd.ps1")
  }

  Invoke-Track "etcd-3" {
    & (Join-Path $PSScriptRoot "run-compare-etcd-3.ps1")
  }

  Invoke-Track "redis" {
    & (Join-Path $PSScriptRoot "run-compare-redis.ps1")
  }
}

Invoke-Track "orchid" {
  Invoke-MvnJmh "OrchidDurableCompareBenchmark,TwoNodeOrchidCommitBenchmark"
}

Invoke-Track "wal" {
  Invoke-MvnJmh "WalCompareBenchmark"
}

Invoke-Track "sealed" {
  & (Join-Path $PSScriptRoot "run-compare-sealed.ps1")
}

Invoke-Track "encode" {
  Invoke-MvnJmh "DuplexCodecBenchmark,DirectVsHeapEncodeBenchmark"
}

Invoke-Track "query" {
  Invoke-MvnJmh "QuerySortCompareBenchmark"
}

if (-not $Fast) {
  Invoke-Track "multi-dc-voters" {
    Invoke-MvnJmh "MultiDcVotersCompareBenchmark"
  }

  Invoke-Track "placement-optimizer" {
    Invoke-MvnJmh "PlacementOptimizerBenchmark"
  }

  Invoke-Track "hazelcast" {
    Invoke-MvnJmh "HazelcastCompareBenchmark"
  }

  Invoke-Track "sql-join-agg" {
    Invoke-MvnJmh "SqlJoinAggPrepareBenchmark"
  }

  Invoke-Track "sql-features" {
    Invoke-MvnJmh "SqlFeaturesBenchmark"
  }

  Invoke-Track "sql-wire-stream" {
    Invoke-MvnJmh "SqlWireStreamBenchmark"
  }

  Invoke-Track "tx-envelope-ship" {
    Invoke-MvnJmh "TxEnvelopeShipBenchmark"
  }
}

$SummaryPath = Join-Path $Results "SUMMARY.md"
& (Join-Path $PSScriptRoot "write-compare-summary.ps1") -Stamp $Stamp -ResultsDir $Results
$summaryExit = $LASTEXITCODE

Write-Host ""
Write-Host "Wrote $SummaryPath"

if ($failed.Count -gt 0) {
  Write-Host "run-compare-all completed with track failures: $($failed -join ', ')"
  exit 1
}
if ($Fast) {
  Write-Host "run-compare-all -Fast OK (smoke). Re-run WITHOUT -Fast for QG stamp."
  exit 0
}
if ($summaryExit -eq 2) {
  Write-Host "run-compare-all OK tracks but SUMMARY gate FAIL (query/encode not yet at-or-below OSS)"
  exit 2
}
Write-Host "run-compare-all OK"
exit 0
