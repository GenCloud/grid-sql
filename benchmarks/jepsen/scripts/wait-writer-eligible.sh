#!/usr/bin/env bash
# Wait until exactly one Active voter reports writerEligible=true on /health/readiness.
# Usage: wait-writer-eligible.sh [httpPort,httpPort,...]
# Default ports: 7777,7778,7779 (Multi-DC a1-a3 / 1-DC n1-n3).
set -euo pipefail

WRITER_SETTLE_DEADLINE_SEC="${WRITER_SETTLE_DEADLINE_SEC:-180}"
WRITER_SETTLE_POLL_SEC="${WRITER_SETTLE_POLL_SEC:-5}"
POST_READY_SLEEP_SEC="${POST_READY_SLEEP_SEC:-8}"
PORTS_CSV="${1:-7777,7778,7779}"
HOST="${WRITER_SETTLE_HOST:-127.0.0.1}"

IFS=',' read -r -a PORTS <<< "$PORTS_CSV"
deadline=$((SECONDS + WRITER_SETTLE_DEADLINE_SEC))
eligible_count=0
last_detail=""

echo "wait-writer-eligible: host=$HOST ports=$PORTS_CSV deadline=${WRITER_SETTLE_DEADLINE_SEC}s"

while (( SECONDS < deadline )); do
  eligible_count=0
  last_detail=""
  for port in "${PORTS[@]}"; do
    port="$(echo "$port" | tr -d '[:space:]')"
    [[ -n "$port" ]] || continue
    body="$(curl -fsS --max-time 3 "http://${HOST}:${port}/health/readiness" 2>/dev/null || true)"
    if [[ -z "$body" ]]; then
      last_detail="${last_detail}${port}=down;"
      continue
    fi
    # Accept writerEligible true in details (Actuator JSON).
    if echo "$body" | grep -Eqi '"writerEligible"[[:space:]]*:[[:space:]]*true'; then
      eligible_count=$((eligible_count + 1))
      last_detail="${last_detail}${port}=eligible;"
    elif echo "$body" | grep -Eqi '"status"[[:space:]]*:[[:space:]]*"UP"'; then
      last_detail="${last_detail}${port}=up-no-writer;"
    else
      last_detail="${last_detail}${port}=not-ready;"
    fi
  done
  if (( eligible_count == 1 )); then
    echo "wait-writer-eligible: OK ($last_detail)"
    sleep "$POST_READY_SLEEP_SEC"
    exit 0
  fi
  echo "wait-writer-eligible: eligible=$eligible_count ($last_detail) retry in ${WRITER_SETTLE_POLL_SEC}s"
  sleep "$WRITER_SETTLE_POLL_SEC"
done

echo "wait-writer-eligible: TIMEOUT eligible=$eligible_count ($last_detail)" >&2
exit 1