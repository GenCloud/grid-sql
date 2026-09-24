#!/usr/bin/env bash
# Perf gate orchestration: TLC Orchid is a hard step; QG / compare / Jepsen optional.
# Usage:
#   ./scripts/run-perf-gate.sh
#   ./scripts/run-perf-gate.sh --compare-all
#   ./scripts/run-perf-gate.sh --jepsen-smoke
#   ./scripts/run-perf-gate.sh --jepsen-full
#   ./scripts/run-perf-gate.sh --skip-tlc   # NOT a valid product gate
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPARE_ALL=0
JEPSEN_SMOKE=0
JEPSEN_FULL=0
SKIP_TLC=0
for arg in "$@"; do
  case "$arg" in
    --compare-all) COMPARE_ALL=1 ;;
    --jepsen-smoke) JEPSEN_SMOKE=1 ;;
    --jepsen-full) JEPSEN_FULL=1 ;;
    --skip-tlc) SKIP_TLC=1 ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown arg: $arg" >&2
      exit 2
      ;;
  esac
done

echo "=== run-perf-gate (TLC hard step) ==="
echo "Root=$ROOT"

FAILED=()

if [[ "$SKIP_TLC" -eq 0 ]]; then
  echo ""
  echo ">>> TLC Orchid (OrchidLog + OrchidLogMultiDc) [HARD]"
  bash "$ROOT/scripts/run-tlc-orchid.sh"
  echo "OK TLC (hard)"
else
  echo "SKIP TLC (--skip-tlc) — not a valid product gate"
fi

if [[ "$COMPARE_ALL" -eq 1 ]]; then
  echo ""
  echo ">>> compare-all (optional)"
  if bash "$ROOT/scripts/run-compare-all.sh"; then
    echo "OK compare-all"
  else
    echo "FAIL compare-all"
    FAILED+=("compare-all")
  fi
fi

if [[ "$JEPSEN_SMOKE" -eq 1 ]]; then
  echo ""
  echo ">>> jepsen-smoke (optional)"
  if bash "$ROOT/scripts/run-jepsen-smoke.sh"; then
    echo "OK jepsen-smoke"
  else
    echo "FAIL jepsen-smoke"
    FAILED+=("jepsen-smoke")
  fi
fi

if [[ "$JEPSEN_FULL" -eq 1 ]]; then
  echo ""
  echo ">>> jepsen full 1-DC (optional; Multi-DC not included)"
  if bash "$ROOT/scripts/run-jepsen.sh"; then
    echo "OK jepsen-full"
  else
    echo "FAIL jepsen-full"
    FAILED+=("jepsen-full")
  fi
fi

echo ""
echo "Gate chain reminder:"
echo "  1) TLC Orchid ........ scripts/run-tlc-orchid.sh|.ps1   [HARD — this script]"
echo "  2) QG SUMMARY ........ (separate QG / query-gate path)"
echo "  3) vs OSS compare .... scripts/run-compare-all.sh|.ps1  [--compare-all]"
echo "  4) Jepsen 1-DC ....... scripts/run-jepsen*.sh|.ps1"
echo "  5) Jepsen Multi-DC ... benchmarks/jepsen/multidc/scripts (not here)"

if ((${#FAILED[@]})); then
  echo "run-perf-gate completed with failures: ${FAILED[*]}"
  exit 1
fi
echo "run-perf-gate OK"
exit 0