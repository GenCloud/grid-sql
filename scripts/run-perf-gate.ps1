# Perf gate orchestration: TLC Orchid is a hard step. QG / compare / Jepsen are optional switches.
# Hard (always): scripts/run-tlc-orchid.ps1 -> OrchidLog + OrchidLogMultiDc (FAIL = blocker)
# Optional: -CompareAll, -JepsenSmoke, -JepsenFull
# Full product gate: TLC + QG SUMMARY + Jepsen 1-DC + Multi-DC + vs OSS (Multi-DC not invoked here).
# Prefer Java 17+ on PATH (project JDK 25). Set JAVA_HOME if needed.
param(
  [switch]$CompareAll,
  [switch]$JepsenSmoke,
  [switch]$JepsenFull,
  [switch]$SkipTlc
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

Write-Host "=== run-perf-gate (TLC hard step) ==="
Write-Host "Root=$Root"

if (-not $SkipTlc) {
  Write-Host ""
  Write-Host ">>> TLC Orchid OrchidLog + OrchidLogMultiDc [HARD]"
  & (Join-Path $PSScriptRoot "run-tlc-orchid.ps1")
  if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
    throw "TLC hard step failed exit=$LASTEXITCODE"
  }
  Write-Host "OK TLC (hard)"
} else {
  Write-Host "SKIP TLC (-SkipTlc) - not a valid product gate"
}

$failed = @()

if ($CompareAll) {
  Write-Host ""
  Write-Host ">>> compare-all (optional)"
  try {
    & (Join-Path $PSScriptRoot "run-compare-all.ps1")
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
      $failed += "compare-all"
      Write-Host "FAIL compare-all exit=$LASTEXITCODE"
    } else {
      Write-Host "OK compare-all"
    }
  } catch {
    $failed += "compare-all"
    Write-Host "FAIL compare-all : $_"
  }
}

if ($JepsenSmoke) {
  Write-Host ""
  Write-Host ">>> jepsen-smoke (optional)"
  try {
    & (Join-Path $PSScriptRoot "run-jepsen-smoke.ps1")
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
      $failed += "jepsen-smoke"
    } else {
      Write-Host "OK jepsen-smoke"
    }
  } catch {
    $failed += "jepsen-smoke"
    Write-Host "FAIL jepsen-smoke : $_"
  }
}

if ($JepsenFull) {
  Write-Host ""
  Write-Host ">>> jepsen full 1-DC (optional - Multi-DC not included)"
  $jepsenSh = Join-Path $PSScriptRoot "run-jepsen.sh"
  if (Get-Command bash -ErrorAction SilentlyContinue) {
    & bash $jepsenSh
    if ($LASTEXITCODE -ne 0) { $failed += "jepsen-full" }
  } else {
    Write-Host "WARN: bash not found - run benchmarks/jepsen scripts manually"
    $failed += "jepsen-full-no-bash"
  }
}

Write-Host ""
Write-Host "Gate chain reminder:"
Write-Host "  1) TLC Orchid ........ scripts/run-tlc-orchid.ps1|.sh   [HARD]"
Write-Host "  2) QG SUMMARY ........ (separate QG / query-gate path)"
Write-Host "  3) vs OSS compare .... scripts/run-compare-all.ps1|.sh  [-CompareAll]"
Write-Host "  4) Jepsen 1-DC ....... scripts/run-jepsen*.ps1|.sh"
Write-Host "  5) Jepsen Multi-DC ... benchmarks/jepsen/multidc/scripts (not here)"

if ($failed.Count -gt 0) {
  Write-Host "run-perf-gate completed with failures: $($failed -join ', ')"
  exit 1
}
Write-Host "run-perf-gate OK"
exit 0