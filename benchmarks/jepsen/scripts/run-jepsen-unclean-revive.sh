#!/usr/bin/env bash
# Jepsen unclean-revive contour: long proposer down + start without mid-run purge.
# Purge only at compose up. Requires JEPSEN_UNCLEAN_REVIVE=1 for nemesis.clj path.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JEPSEN_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=jepsen-instance-env.sh
. "$JEPSEN_DIR/scripts/jepsen-instance-env.sh"

export JEPSEN_UNCLEAN_REVIVE=1
export JEPSEN_UNCLEAN_DOWN_SEC="${JEPSEN_UNCLEAN_DOWN_SEC:-15}"
export STAMP="${STAMP:-$(date +%Y-%m-%d)-jepsen-unclean-revive}"

TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
export JEPSEN_TIME_LIMIT="$TIME_LIMIT"

FAST=0
SKIP_REBUILD=0
SETTLE_SEC=0
EXTRA=()
for arg in "$@"; do
  case "$arg" in
    --fast|-Fast) FAST=1 ;;
    --skip-rebuild|-SkipRebuild) SKIP_REBUILD=1 ;;
    --rebuild|-Rebuild) EXTRA+=(--rebuild) ;;
    --settle=*) SETTLE_SEC="${arg#--settle=}" ;;
    *) EXTRA+=("$arg") ;;
  esac
done

if [[ "$FAST" == "1" ]]; then
  SKIP_REBUILD=1
fi
if [[ "$SKIP_REBUILD" == "1" ]]; then
  export JEPSEN_REBUILD=0
else
  : "${JEPSEN_REBUILD:=0}"
fi

# Optional settle sleep after ensure_cluster lives in run-jepsen.ps1; bash path uses run-jepsen.sh.
if [[ "$SETTLE_SEC" != "0" ]]; then
  export JEPSEN_SETTLE_SEC="$SETTLE_SEC"
fi

set +e
bash "$SCRIPT_DIR/run-jepsen.sh" "${EXTRA[@]}"
code=$?
set -e

RESULTS="$JEPSEN_DIR/RESULTS.md"
if [[ -f "$RESULTS" ]]; then
  if ! grep -q "unclean-revive" "$RESULTS"; then
    # Best-effort annotate latest history block; stamp-results may already have written STAMP.
    true
  fi
fi

exit "$code"
