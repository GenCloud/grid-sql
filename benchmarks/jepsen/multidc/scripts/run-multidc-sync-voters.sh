#!/usr/bin/env bash
# Multi-DC Jepsen — SYNC_VOTERS_ACROSS_DC
set -euo pipefail
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$MULTIDC_DIR"

export MULTIDC_MODE=sync-voters
TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"

echo "=== Multi-DC SYNC_VOTERS_ACROSS_DC ==="
docker compose config >/dev/null
echo "compose config OK (MULTIDC_MODE=$MULTIDC_MODE)"

if [[ "${MULTIDC_FULL:-0}" != "1" ]]; then
  echo "Scaffold only. Set MULTIDC_FULL=1 (prefer PowerShell run-multidc-sync-voters.ps1 -Full on Windows)."
  exit 0
fi

if command -v pwsh >/dev/null 2>&1; then
  exec pwsh -NoProfile -File "$MULTIDC_DIR/scripts/run-multidc-full.ps1" -Mode sync-voters -Full -TimeLimit "$TIME_LIMIT"
fi

echo "ERROR: MULTIDC_FULL=1 without pwsh — use run-multidc-sync-voters.ps1 -Full or install PowerShell 7."
exit 2