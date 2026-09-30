#!/usr/bin/env bash
# Multi-DC SYNC_VOTERS — FULL defaults to chaos (nemesis ON). Use run-multidc-nochao.sh for no-nemesis.
set -euo pipefail
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$MULTIDC_DIR"
export MULTIDC_MODE=sync-voters
export JEPSEN_M2="${JEPSEN_M2:-${HOME}/.m2}"
TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
echo "=== Multi-DC SYNC_VOTERS_ACROSS_DC ==="
docker compose config >/dev/null
echo "compose config OK (MULTIDC_MODE=$MULTIDC_MODE)"
if [[ "${MULTIDC_FULL:-0}" != "1" ]]; then
  echo "Scaffold only. Set MULTIDC_FULL=1 for Docker+lein."
  exit 0
fi
exec bash "$MULTIDC_DIR/scripts/run-multidc-chaos.sh" sync-voters