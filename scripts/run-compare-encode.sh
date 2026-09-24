#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JMH_STAMP="${JMH_STAMP:-$(date +%Y-%m-%d)}"
cd "$ROOT"
mvn -pl grid-server-core -Dtest=DuplexCodecBenchmark test
