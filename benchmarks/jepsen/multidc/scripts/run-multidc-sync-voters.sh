#!/usr/bin/env bash
# Multi-DC Jepsen — SYNC_VOTERS_ACROSS_DC
# Bash FULL path is the CI default (multidc-common.sh). Set MULTIDC_PREFER_PWSH=1 to exec PowerShell.
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
  echo "Scaffold only. Set MULTIDC_FULL=1 (prefer PowerShell run-multidc-sync-voters.ps1 -Full on Windows)."
  exit 0
fi

if [[ "${MULTIDC_PREFER_PWSH:-0}" == "1" ]] && command -v pwsh >/dev/null 2>&1; then
  exec pwsh -NoProfile -File "$MULTIDC_DIR/scripts/run-multidc-full.ps1" -Mode sync-voters -Full -TimeLimit "$TIME_LIMIT"
fi

echo "FULL bash path: multidc-common.sh (CI-stable; no pwsh required)"
exec bash "$MULTIDC_DIR/scripts/multidc-common.sh" sync-voters