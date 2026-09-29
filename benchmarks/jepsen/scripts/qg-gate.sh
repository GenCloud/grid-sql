#!/usr/bin/env bash
# Multi-percentile algorithm gate vs Ref B (sql-v11 best nochao). Pure bash (no pwsh).
# Hard: p50 + p95. Soft: p99. On GITHUB_ACTIONS / QG_GATE_CI_ADVISORY=1: p95 fail is advisory.
# Usage: qg-gate.sh -RegisterHistory PATH -AppendHistory PATH
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REGISTER_HISTORY=""
APPEND_HISTORY=""
WARMUP=10
TOL=0.05
P99_TOL=0.15
STRICT_P99=0
MIN_OK_WRITE=0
MIN_OK_APPEND=0
CI_ADVISORY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -RegisterHistory|--register-history) REGISTER_HISTORY="$2"; shift 2 ;;
    -AppendHistory|--append-history) APPEND_HISTORY="$2"; shift 2 ;;
    -WarmupSeconds) WARMUP="$2"; shift 2 ;;
    -Tol) TOL="$2"; shift 2 ;;
    -P99Tol) P99_TOL="$2"; shift 2 ;;
    -StrictP99) STRICT_P99=1; shift ;;
    -MinOkWrite) MIN_OK_WRITE="$2"; shift 2 ;;
    -MinOkAppend) MIN_OK_APPEND="$2"; shift 2 ;;
    -CiAdvisory) CI_ADVISORY=1; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$REGISTER_HISTORY" && -n "$APPEND_HISTORY" ]] || {
  echo "Usage: qg-gate.sh -RegisterHistory PATH -AppendHistory PATH" >&2
  exit 2
}

if [[ "${GITHUB_ACTIONS:-}" == "true" || "${QG_GATE_CI_ADVISORY:-}" == "1" ]]; then
  CI_ADVISORY=1
fi

# Ref B living floor — do not raise for CI green
REF_WRITE_P50=12.7
REF_WRITE_P95=88.1
REF_WRITE_P99=252.4
REF_APPEND_P50=11.9
REF_APPEND_P95=36.8
REF_APPEND_P99=47.4
REF_TXN_R_P95=25.0
REF_TXN_R_P99=55.0

parse_lat() {
  local file="$1" workload="$2"
  bash "$SCRIPT_DIR/latency-from-history.sh" "$file" "$workload" "$WARMUP"
}

get_field() {
  # stdin lines → stdout value for "op: n=.. p50=Xms p95=Yms p99=Zms" field p50|p95|p99|n
  local op="$1" field="$2"
  awk -v op="$op" -v field="$field" '
    $0 ~ ("^" op ":") {
      if (field == "n" && match($0, /n=[0-9]+/)) { s=substr($0,RSTART+2,RLENGTH-2); print s; exit }
      if (field == "p50" && match($0, /p50=[0-9.]+/)) { s=substr($0,RSTART+4,RLENGTH-4); print s; exit }
      if (field == "p95" && match($0, /p95=[0-9.]+/)) { s=substr($0,RSTART+4,RLENGTH-4); print s; exit }
      if (field == "p99" && match($0, /p99=[0-9.]+/)) { s=substr($0,RSTART+4,RLENGTH-4); print s; exit }
    }
  '
}

REG_OUT="$(parse_lat "$REGISTER_HISTORY" register)"
APP_OUT="$(parse_lat "$APPEND_HISTORY" append)"

W_P50="$(echo "$REG_OUT" | get_field write p50 || true)"
W_P95="$(echo "$REG_OUT" | get_field write p95 || true)"
W_P99="$(echo "$REG_OUT" | get_field write p99 || true)"
W_N="$(echo "$REG_OUT" | get_field write n || true)"; W_N="${W_N:-0}"
A_P50="$(echo "$APP_OUT" | get_field txn_append p50 || true)"
A_P95="$(echo "$APP_OUT" | get_field txn_append p95 || true)"
A_P99="$(echo "$APP_OUT" | get_field txn_append p99 || true)"
A_N="$(echo "$APP_OUT" | get_field txn_append n || true)"; A_N="${A_N:-0}"
R_P95="$(echo "$APP_OUT" | get_field txn_r p95 || true)"
R_P99="$(echo "$APP_OUT" | get_field txn_r p99 || true)"
R_N="$(echo "$APP_OUT" | get_field txn_r n || true)"; R_N="${R_N:-0}"

HARD_FAIL=0
SOFT_FAIL=0
P50_HARD_FAIL=0

limit() {
  awk -v b="$1" -v t="$2" 'BEGIN { printf "%.6f", b * (1.0 + t) }'
}

check() {
  local name="$1" got="$2" base="$3" kind="$4"
  if [[ -z "$got" || "$got" == "n/a" ]]; then
    printf "  %-14s got=n/a limit=n/a kind=%s result=SKIP\n" "$name" "$kind"
    return
  fi
  local lim
  if [[ "$kind" == "hard" ]]; then lim="$(limit "$base" "$TOL")"; else lim="$(limit "$base" "$P99_TOL")"; fi
  local ok
  ok="$(awk -v g="$got" -v l="$lim" 'BEGIN { print (g+0 <= l+0) ? 1 : 0 }')"
  local res=PASS
  if [[ "$ok" != "1" ]]; then
    res=FAIL
    if [[ "$kind" == "hard" ]]; then
      HARD_FAIL=1
      if [[ "$name" == *p50* ]]; then P50_HARD_FAIL=1; fi
    else
      SOFT_FAIL=1
    fi
  fi
  printf "  %-14s got=%s limit=%s kind=%s result=%s\n" "$name" "$got" "$lim" "$kind" "$res"
}

MODE_NOTE="mode=HOST_HARD"
if [[ "$CI_ADVISORY" == "1" ]]; then
  MODE_NOTE="mode=CI_ADVISORY (p95 fail does not exit 1; Ref B p95 living floor = calm host)"
fi
echo "=== qg-gate Ref B (tol p50/p95=+$(awk -v t="$TOL" 'BEGIN{printf "%d", t*100}')% p99=+$(awk -v t="$P99_TOL" 'BEGIN{printf "%d", t*100}')%) $MODE_NOTE ==="
echo "register write n=$W_N p50=$W_P50 p95=$W_P95 p99=$W_P99"
echo "append n=$A_N p50=$A_P50 p95=$A_P95 p99=$A_P99"
echo "txn_r n=$R_N p50= p95=$R_P95 p99=$R_P99"

check "write p50" "$W_P50" "$REF_WRITE_P50" hard
check "write p95" "$W_P95" "$REF_WRITE_P95" hard
check "append p50" "$A_P50" "$REF_APPEND_P50" hard
check "append p95" "$A_P95" "$REF_APPEND_P95" hard
check "txn_r p95" "$R_P95" "$REF_TXN_R_P95" hard
check "write p99" "$W_P99" "$REF_WRITE_P99" soft
check "append p99" "$A_P99" "$REF_APPEND_P99" soft
check "txn_r p99" "$R_P99" "$REF_TXN_R_P99" soft

if [[ "$MIN_OK_WRITE" -gt 0 && "$W_N" -lt "$MIN_OK_WRITE" ]]; then
  HARD_FAIL=1; P50_HARD_FAIL=1
  echo "  n write FAIL got=$W_N limit=$MIN_OK_WRITE"
fi
if [[ "$MIN_OK_APPEND" -gt 0 && "$A_N" -lt "$MIN_OK_APPEND" ]]; then
  HARD_FAIL=1; P50_HARD_FAIL=1
  echo "  n append FAIL got=$A_N limit=$MIN_OK_APPEND"
fi

OVERALL=PASS
if [[ "$HARD_FAIL" == "1" ]]; then OVERALL=FAIL
elif [[ "$STRICT_P99" == "1" && "$SOFT_FAIL" == "1" ]]; then OVERALL=FAIL
elif [[ "$SOFT_FAIL" == "1" ]]; then OVERALL=PASS_WITH_P99_WARN
fi

if [[ "$CI_ADVISORY" == "1" && "$HARD_FAIL" == "1" && "$P50_HARD_FAIL" != "1" ]]; then
  echo "OVERALL=CI_ADVISORY_P95_FAIL"
  echo "NOTE: Ref B p95 living floor unchanged - re-stamp on calm host. Do not raise Ref B for CI green."
  exit 0
fi

echo "OVERALL=$OVERALL"
if [[ "$OVERALL" == "FAIL" ]]; then exit 1; fi
exit 0