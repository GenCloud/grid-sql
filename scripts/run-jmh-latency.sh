#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/grid-server-core"
RESULTS="$MODULE/benchmarks/lab"
mkdir -p "$RESULTS"

STAMP="${JMH_STAMP:-$(date +%Y-%m-%d)}"
BENCHES=(
  OrchidCommitLatencyBenchmark
  TwoNodeOrchidCommitBenchmark
  OpLogAppendBenchmark
  DuplexCodecBenchmark
  ReplicaReadLatencyBenchmark
)
if [[ "${JMH_INCLUDE_LAX:-0}" == "1" ]]; then
  BENCHES+=(LaxGridCompositeIndexBenchmarkTest)
fi

FINGERPRINT="$RESULTS/RESULTS.md"
{
  echo "# JMH latency results"
  echo
  echo "## Machine fingerprint"
  echo
  echo "- Stamp: $STAMP"
  echo "- OS: $(uname -a)"
  echo "- CPU: $( (sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo 2>/dev/null || echo unknown) )"
  echo "- Java:"
  echo '```'
  java -version 2>&1
  echo '```'
  echo
  echo "## Suite"
  echo
  echo 'Latency harness: `AbstractLatencyBenchmark` (forks=1, threads=1, warmup 2×1s, measure 3×1s).'
  echo
} > "$FINGERPRINT"

export JMH_STAMP="$STAMP"
cd "$ROOT"
for b in "${BENCHES[@]}"; do
  echo "=== JMH $b ==="
  mvn -pl grid-server-core -Dtest="$b" test
done

echo "JSON under $RESULTS (${STAMP}-latency-*.json)"
ls -1 "$RESULTS"/${STAMP}-latency-*.json 2>/dev/null || true
