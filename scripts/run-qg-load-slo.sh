#!/usr/bin/env bash
# DEPRECATED: forwards to scripts/run-jmeter-load-slo.sh (Apache JMeter + grid-sql-client).
# Calm host only - never parallel with QG / Jepsen / OSS peers / JMH.
set -euo pipefail
echo "WARNING: run-qg-load-slo.sh is DEPRECATED - forwarding to run-jmeter-load-slo.sh" >&2
ROOT="$(cd "$(dirname "$0")" && pwd)"
exec "$ROOT/run-jmeter-load-slo.sh" "$@"