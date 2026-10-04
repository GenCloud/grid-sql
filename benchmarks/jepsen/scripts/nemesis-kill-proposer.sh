#!/usr/bin/env bash
# Kill a proposer selected by Jepsen wire ServerMeta discovery.
# /replication/status is intentionally not used for promotion decisions; HTTP is liveness-only.
set -eu
PROPOSER="${1:-${JEPSEN_PROPOSER_NODE:-}}"
ONE_DC_PREFIX="${JEPSEN_1DC_CTR_PREFIX:-jamoa-jepsen}"
MDC_PREFIX="${JEPSEN_MDC_CTR_PREFIX:-jamoa-multidc}"

if [[ -z "$PROPOSER" ]]; then
  if [[ "${JEPSEN_MULTIDC:-0}" == "1" ]]; then
    echo "No wire-discovered proposer supplied; killing a1 as best-effort fallback"
    PROPOSER="a1"
  else
    echo "No wire-discovered proposer supplied; killing n1 as best-effort fallback"
    PROPOSER="n1"
  fi
fi

if [[ "${JEPSEN_MULTIDC:-0}" == "1" ]]; then
  CTR="${MDC_PREFIX}-${PROPOSER}"
else
  CTR="${ONE_DC_PREFIX}-${PROPOSER}"
fi
echo "Killing proposer container $CTR"
docker kill "$CTR" || docker stop "$CTR" || true
# Restart so cluster can recover for subsequent ops
sleep 2
docker start "$CTR" || true