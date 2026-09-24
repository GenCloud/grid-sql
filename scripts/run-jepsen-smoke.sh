#!/usr/bin/env bash
exec "$(cd "$(dirname "$0")/.." && pwd)/benchmarks/jepsen/scripts/run-smoke.sh"
