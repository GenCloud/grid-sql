#!/usr/bin/env bash
# Multi-DC ASYNC_SHIP — FULL defaults to chaos (nemesis ON). Use run-multidc-nochao.sh for no-nemesis.
set -euo pipefail
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$MULTIDC_DIR"
export MULTIDC_MODE=async
export JEPSEN_M2="${JEPSEN_M2:-${HOME}/.m2}"
TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
echo "=== Multi-DC ASYNC_SHIP ==="
docker compose config >/dev/null
echo "compose config OK (MULTIDC_MODE=$MULTIDC_MODE)"
if [[ "${MULTIDC_FULL:-0}" != "1" ]]; then
  echo "Scaffold only. Set MULTIDC_FULL=1 for Docker+lein."
  exit 0
fi
exec bash "$MULTIDC_DIR/scripts/run-multidc-chaos.sh" async