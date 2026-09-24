#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JMH_STAMP="${JMH_STAMP:-$(date +%F)}"
ROWS="${SEALED_BENCH_ROWS:-1000,100000,1000000}"
cd "$ROOT"
mvn -pl grid-server-core -Dtest=SealedQueryPathBenchmark -Dsealed.bench.rows="$ROWS" \
  -Dsealed.bench.modes=memory,disk,hybrid \
  -Dsurefire.forkedProcessTimeoutInSeconds=3600 test