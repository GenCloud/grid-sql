#!/usr/bin/env bash
# Write / update RESULTS.md stamp.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
STAMP="${STAMP:-$(date +%Y-%m-%d)-jepsen-smoke}"
MODE="${MODE:-smoke}"
OUTCOME="${OUTCOME:-HARNESS_READY}"
COMPOSE_STATUS="${COMPOSE_STATUS:-validated}"
CHAOS="${CHAOS:-skipped}"
FULL="${FULL:-not-run}"
NOTES="${NOTES:-}"
GIT="$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
HOST="$(hostname 2>/dev/null || echo unknown)"
DATE="$(date -Iseconds 2>/dev/null || date)"

RESULTS="$ROOT/RESULTS.md"
TMP="$(mktemp)"
cat > "$TMP" <<EOF
# Jepsen RESULTS

Stamp template - filled by \`scripts/run-jepsen-smoke.*\` or a full Jepsen run.

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | ${STAMP} |
| date | ${DATE} |
| git | ${GIT} |
| host | ${HOST} |
| mode | \`${MODE}\` |
| outcome | \`${OUTCOME}\` |
| notes | ${NOTES} |

## History

EOF

# Keep prior history blocks (everything after first "### " in old file)
if [[ -f "$RESULTS" ]] && grep -q '^### ' "$RESULTS"; then
  awk 'p{print} /^### /{p=1; print}' "$RESULTS" >> "$TMP"
fi

{
  echo ""
  echo "### ${STAMP}"
  echo "- mode: ${MODE}"
  echo "- outcome: ${OUTCOME}"
  echo "- git: ${GIT}"
  echo "- compose: ${COMPOSE_STATUS}"
  echo "- chaos-it: ${CHAOS}"
  echo "- full-jepsen: ${FULL}"
  echo "- command: ${COMMAND:-run-jepsen-smoke}"
  echo "- notes: ${NOTES}"
} >> "$TMP"

mv "$TMP" "$RESULTS"
echo "Wrote $RESULTS stamp=$STAMP outcome=$OUTCOME"
