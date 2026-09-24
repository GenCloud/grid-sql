#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JMH_STAMP="${JMH_STAMP:-$(date +%Y-%m-%d)}"
cd "$ROOT"
SETTINGS="$ROOT/benchmarks/compare/maven-central-settings.xml"
mvn -s "$SETTINGS" -pl grid-server-core -Dtest=QuerySortCompareBenchmark test
