#!/usr/bin/env bash
# TLC Orchid hard step is NOT in this script. Use scripts/run-perf-gate.sh (calls run-tlc-orchid) before or after compare.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SETTINGS="$ROOT/benchmarks/compare/maven-central-settings.xml"
export JMH_STAMP="${JMH_STAMP:-$(date +%Y-%m-%d)-compare}"
RESULTS="$ROOT/grid-server-core/benchmarks/lab"
mkdir -p "$RESULTS"
cd "$ROOT"

FAILED=()
run_track() {
  local name="$1"; shift
  echo ""
  echo ">>> $name"
  if "$@"; then
    echo "OK $name"
  else
    echo "FAIL $name"
    FAILED+=("$name")
  fi
}

run_track etcd bash "$ROOT/scripts/run-compare-etcd.sh" || true
run_track etcd-3 bash "$ROOT/scripts/run-compare-etcd-3.sh" || true
run_track redis bash "$ROOT/scripts/run-compare-redis.sh" || true
run_track orchid mvn -s "$SETTINGS" -pl grid-server-core -Dtest=OrchidDurableCompareBenchmark,TwoNodeOrchidCommitBenchmark test || true
run_track wal mvn -s "$SETTINGS" -pl grid-server-core -Dtest=WalCompareBenchmark test || true
run_track sealed bash "$ROOT/scripts/run-compare-sealed.sh" || true
run_track encode mvn -s "$SETTINGS" -pl grid-server-core -Dtest=DuplexCodecBenchmark,DirectVsHeapEncodeBenchmark test || true
run_track query mvn -s "$SETTINGS" -pl grid-server-core -Dtest=QuerySortCompareBenchmark test || true
run_track multi-dc-voters mvn -s "$SETTINGS" -pl grid-server-core -Dtest=MultiDcVotersCompareBenchmark test || true
run_track placement-optimizer mvn -s "$SETTINGS" -pl grid-server-core -Dtest=PlacementOptimizerBenchmark test || true
run_track hazelcast mvn -s "$SETTINGS" -pl grid-server-core -Dtest=HazelcastCompareBenchmark test || true
run_track sql-join-agg mvn -s "$SETTINGS" -pl grid-server-core -Dtest=SqlJoinAggPrepareBenchmark test || true
run_track sql-wire-stream mvn -s "$SETTINGS" -pl grid-server-core -Dtest=SqlWireStreamBenchmark test || true
run_track tx-envelope-ship mvn -s "$SETTINGS" -pl grid-server-core -Dtest=TxEnvelopeShipBenchmark test || true

SUMMARY_EXIT=0
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoProfile -File "$ROOT/scripts/write-compare-summary.ps1" -Stamp "$JMH_STAMP" -ResultsDir "$RESULTS" || SUMMARY_EXIT=$?
elif command -v powershell >/dev/null 2>&1; then
  powershell -NoProfile -File "$ROOT/scripts/write-compare-summary.ps1" -Stamp "$JMH_STAMP" -ResultsDir "$RESULTS" || SUMMARY_EXIT=$?
else
  {
    echo "# OSS compare SUMMARY"
    echo ""
    echo "- Stamp: \`$JMH_STAMP\`"
    echo "- Generated: $(date -Iseconds 2>/dev/null || date)"
  } > "$RESULTS/SUMMARY.md"
fi

echo "Wrote $RESULTS/SUMMARY.md"
if ((${#FAILED[@]})); then
  echo "run-compare-all failures: ${FAILED[*]}"
  exit 1
fi
if [[ "$SUMMARY_EXIT" -eq 2 ]]; then
  echo "run-compare-all OK tracks but SUMMARY gate FAIL"
  exit 2
fi
echo "run-compare-all OK"