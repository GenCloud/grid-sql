#!/usr/bin/env bash
# Kill proposer and leave it down longer before start — unclean volume revive (no purge).
# Usage: nemesis-unclean-revive.sh [nodeId]
# Container: 1-DC ${JEPSEN_1DC_CTR_PREFIX}-{id}; Multi-DC ${JEPSEN_MDC_CTR_PREFIX}-{id}
set -eu
PROPOSER="${1:-${JEPSEN_PROPOSER_NODE:-}}"
DOWN_SEC="${JEPSEN_UNCLEAN_DOWN_SEC:-15}"
ONE_DC_PREFIX="${JEPSEN_1DC_CTR_PREFIX:-jamoa-jepsen}"
MDC_PREFIX="${JEPSEN_MDC_CTR_PREFIX:-jamoa-multidc}"

if [[ -z "$PROPOSER" ]]; then
  if [[ "${JEPSEN_MULTIDC:-0}" == "1" ]]; then
    echo "No wire-discovered proposer; killing a1 as best-effort fallback"
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
echo "Unclean revive: kill $CTR, down ${DOWN_SEC}s, start (no purge)"
docker kill "$CTR" || docker stop "$CTR" || true
sleep "$DOWN_SEC"
docker start "$CTR" || true
sleep 3