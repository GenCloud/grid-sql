#!/usr/bin/env bash
# Parse Jepsen history.edn latencies (ok ops after warmup). Pure bash+awk (no pwsh/python).
# Usage: latency-from-history.sh HISTORY_EDN [WORKLOAD] [WARMUP_SEC]
set -euo pipefail
HISTORY_EDN="${1:?history.edn path required}"
WORKLOAD="${2:-register}"
WARMUP_SEC="${3:-10}"
WARM_NS=$((WARMUP_SEC * 1000000000))

if [[ ! -f "$HISTORY_EDN" ]]; then
  echo "ERROR: missing history $HISTORY_EDN" >&2
  exit 1
fi

# Flatten EDN ops to lines: type process time f appendFlag
# Matches :type :ok|:fail|:info|:invoke, :process N, :time N, :f :word, optional :append
awk -v warm="$WARM_NS" -v workload="$WORKLOAD" '
BEGIN {
  ok=0; fail=0; info=0
}
{
  line=$0
  # Scan for type tokens in map blobs (history may be multi-line)
  while (match(line, /:type[[:space:]]+:(invoke|ok|fail|info)/)) {
    typ = substr(line, RSTART, RLENGTH)
    sub(/.*:type[[:space:]]+:/, "", typ)
    rest = substr(line, RSTART + RLENGTH)
    # Try to find process/time/f on same brace depth chunk — read ahead into buf
    # For multi-line maps, accumulate until closing }
    chunk = typ
    # Use current line from match start to end, plus continue
    chunk_line = substr(line, RSTART)
    # Find matching } — simplistic: take until first } that closes
    # Better: process whole file as one string
    line = rest
    break
  }
}
' "$HISTORY_EDN" >/dev/null 2>&1 || true

# Single-pass: convert file to one line records via RS on ":type"
# Extract ops with grep -oE style into a temp TSV
TMP="$(mktemp)"
# history.edn often one map per line or pretty-printed; normalize to one map per line
# Use awk with RS="" and FS — walk character-wise for balanced braces is heavy.
# Practical approach: for each line containing :type, extract fields from that line + next few.
awk '
function flush() {
  if (typ == "") return
  appendf = (chunk ~ /:append/) ? 1 : 0
  if (match(chunk, /:process[[:space:]]+[0-9]+/)) {
    p = substr(chunk, RSTART, RLENGTH); sub(/.*[[:space:]]/, "", p)
  } else p = ""
  if (match(chunk, /:time[[:space:]]+[0-9]+/)) {
    t = substr(chunk, RSTART, RLENGTH); sub(/.*[[:space:]]/, "", t)
  } else t = ""
  if (match(chunk, /:f[[:space:]]+:[a-zA-Z_]+/)) {
    f = substr(chunk, RSTART, RLENGTH); sub(/.*:/, "", f)
  } else f = ""
  if (p != "" && t != "" && f != "")
    print typ "\t" p "\t" t "\t" f "\t" appendf
  typ=""; chunk=""
}
{
  for (i = 1; i <= length($0); i++) {
    c = substr($0, i, 1)
    if (c == "{") {
      depth++
      if (depth == 1) { typ=""; chunk="{"; inmap=1; continue }
    }
    if (inmap) chunk = chunk c
    if (c == "}") {
      depth--
      if (depth == 0 && inmap) {
        if (match(chunk, /:type[[:space:]]+:(invoke|ok|fail|info)/)) {
          typ = substr(chunk, RSTART, RLENGTH)
          sub(/.*:/, "", typ)
          flush()
        }
        inmap=0; chunk=""
      }
    }
  }
}
' "$HISTORY_EDN" > "$TMP"

awk -v warm="$WARM_NS" -v workload="$WORKLOAD" '
BEGIN { FS="\t"; ok=0; fail=0; info=0 }
$1 == "invoke" { pending[$2] = $3 SUBSEP $4 SUBSEP $5; next }
$1 == "fail" { fail++; delete pending[$2]; next }
$1 == "info" { info++; delete pending[$2]; next }
$1 == "ok" {
  ok++
  if (!($2 in pending)) next
  split(pending[$2], inv, SUBSEP)
  delete pending[$2]
  inv_t = inv[1] + 0
  inv_f = inv[2]
  inv_ap = inv[3] + 0
  if (inv_t < warm) next
  lat = ($3 - inv_t) / 1e6
  bucket = inv_f
  if (bucket == "txn") {
    if (inv_ap == 1 || $5 == 1) bucket = "txn_append"
    else bucket = "txn_r"
  }
  n[bucket]++
  # store latencies in parallel arrays keyed by index
  idx = n[bucket]
  vals[bucket, idx] = lat
}
function pct(bucket, p,    i, j, tmp, cnt, arr) {
  cnt = n[bucket] + 0
  if (cnt == 0) return -1
  for (i = 1; i <= cnt; i++) arr[i] = vals[bucket, i]
  for (i = 1; i <= cnt; i++)
    for (j = i + 1; j <= cnt; j++)
      if (arr[i] > arr[j]) { tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp }
  i = int((p / 100.0) * (cnt - 1))
  if (i < 1) i = 1
  if (i > cnt) i = cnt
  # 0-based floor: Floor((p/100)*(n-1)) in 1-based = int(...)+1? PS used Floor then index
  i = int((p / 100.0) * (cnt - 1)) + 1
  if (i < 1) i = 1
  if (i > cnt) i = cnt
  return arr[i]
}
function report(name,    c, p50, p95, p99) {
  c = n[name] + 0
  if (c == 0) { print name ": n=0"; return }
  p50 = pct(name, 50); p95 = pct(name, 95); p99 = pct(name, 99)
  printf "%s: n=%d p50=%.3fms p95=%.3fms p99=%.3fms\n", name, c, p50, p95, p99
}
END {
  printf "workload=%s warmupDrop=%ds fail=%d info=%d ok=%d\n", workload, warm/1e9, fail, info, ok
  report("read")
  report("write")
  report("txn_r")
  report("txn_append")
}
' "$TMP"
rm -f "$TMP"