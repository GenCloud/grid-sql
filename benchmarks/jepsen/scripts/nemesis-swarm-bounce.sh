#!/usr/bin/env bash
# Bounce a non-proposer follower to stress swarm catch-up / opportunistic cutover.
# 1-DC / Multi-DC prefixes from JEPSEN_*_CTR_PREFIX (parallel-cell aware).
set -eu
PROPOSER="${1:-${JEPSEN_PROPOSER_NODE:-}}"
ONE_DC_PREFIX="${JEPSEN_1DC_CTR_PREFIX:-jamoa-jepsen}"
MDC_PREFIX="${JEPSEN_MDC_CTR_PREFIX:-jamoa-multidc}"
MULTIDC=0
if [[ "${JEPSEN_MULTIDC:-0}" == "1" ]] || docker inspect "${MDC_PREFIX}-a1" >/dev/null 2>&1; then
  MULTIDC=1
fi

if [[ "$MULTIDC" == "1" ]]; then
  if [[ -z "$PROPOSER" ]]; then PROPOSER="a1"; fi
  TARGET="a2"
  case "$PROPOSER" in
    a2) TARGET="a3" ;;
    a3) TARGET="a2" ;;
    a1) TARGET="a2" ;;
    *) TARGET="a2" ;;
  esac
  CTR="${MDC_PREFIX}-${TARGET}"
else
  if [[ -z "$PROPOSER" ]]; then PROPOSER="n1"; fi
  TARGET="n2"
  if [[ "$PROPOSER" == "n2" ]]; then TARGET="n3"; fi
  if [[ "$PROPOSER" == "n3" ]]; then TARGET="n2"; fi
  CTR="${ONE_DC_PREFIX}-${TARGET}"
fi

echo "swarm-bounce: kill/start follower $CTR (proposer hint=$PROPOSER multidc=$MULTIDC)"
docker kill "$CTR" || docker stop "$CTR" || true
sleep 3
docker start "$CTR" || true
sleep 2