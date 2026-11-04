#!/usr/bin/env bash
# Join-shards honesty: definite schema/SQL miss must FAIL the profile even if Elle :valid? true.
# Usage: jepsen-honesty-gate.sh <lein-stdout-file>
# Exit 0 = honest (no schema spam); exit 1 = FAIL honesty; prints HISTOGRAM=... notes line.
set -euo pipefail

OUT_FILE="${1:?lein output file required}"
SCHEMA_PATTERNS='Unknown column|schema-error|unknown join column|Unknown column in projection|Unknown table'
# Catalog epoch / lag desync (cells N/O/P) — never hide behind Elle :valid? true.
CATALOG_PATTERNS='catalog apply lag|peer catalog ahead|stale schema epoch|catalog_lag_stuck|catalogAppliedEpoch'

schema_hits=0
catalog_hits=0
if [[ -f "$OUT_FILE" ]]; then
  schema_hits="$(grep -Eic "$SCHEMA_PATTERNS" "$OUT_FILE" 2>/dev/null || true)"
  if [[ "${JEPSEN_CATALOG_HONESTY:-0}" == "1" ]]; then
    catalog_hits="$(grep -Eic "$CATALOG_PATTERNS" "$OUT_FILE" 2>/dev/null || true)"
  fi
fi
schema_hits="${schema_hits:-0}"
catalog_hits="${catalog_hits:-0}"

hist=""
if [[ -f "$OUT_FILE" ]]; then
  hist="$(grep -Eo ':error[[:space:]]+[^]}]+' "$OUT_FILE" 2>/dev/null \
    | sed 's/^:error[[:space:]]*//' \
    | sort | uniq -c | sort -rn | head -8 \
    | tr '\n' ';' | sed 's/;$//')" || true
fi
echo "HISTOGRAM=schema_hits=${schema_hits};catalog_hits=${catalog_hits};${hist}"

if [[ "$schema_hits" -gt 0 ]]; then
  echo "HONESTY_FAIL=schema-error-in-history-or-log count=${schema_hits}"
  exit 1
fi
if [[ "$catalog_hits" -gt 0 ]]; then
  echo "HONESTY_FAIL=catalog-epoch-desync-in-history-or-log count=${catalog_hits}"
  exit 1
fi
exit 0
