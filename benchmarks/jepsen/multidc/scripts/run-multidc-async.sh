#!/usr/bin/env bash
# Multi-DC Jepsen — ASYNC_SHIP (scaffold or MULTIDC_FULL=1 → Docker+lein).
# Bash FULL path is the CI default (multidc-common.sh). Set MULTIDC_PREFER_PWSH=1 to exec PowerShell.
set -euo pipefail
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$MULTIDC_DIR"

export MULTIDC_MODE=async
export JEPSEN_M2="${JEPSEN_M2:-${HOME}/.m2}"
TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"

echo "=== Multi-DC ASYNC_SHIP ==="
echo "SQL URL: grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public"
echo "Validating docker compose config..."
docker compose config >/dev/null
echo "compose config OK (MULTIDC_MODE=$MULTIDC_MODE)"

if [[ "${MULTIDC_FULL:-0}" != "1" ]]; then
  echo "Scaffold only. Set MULTIDC_FULL=1 for Docker+lein (prefer PowerShell run-multidc-async.ps1 -Full on Windows)."
  exit 0
fi

if [[ "${MULTIDC_PREFER_PWSH:-0}" == "1" ]] && command -v pwsh >/dev/null 2>&1; then
  exec pwsh -NoProfile -File "$MULTIDC_DIR/scripts/run-multidc-full.ps1" -Mode async -Full -TimeLimit "$TIME_LIMIT"
fi

echo "FULL bash path: multidc-common.sh (CI-stable; no pwsh required)"
exec bash "$MULTIDC_DIR/scripts/multidc-common.sh" async