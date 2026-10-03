#!/usr/bin/env bash
# Bounce a non-proposer follower to stress swarm catch-up / opportunistic cutover.
# 1-DC: jamoa-jepsen-n{1,2,3}. Multi-DC Active: jamoa-multidc-a{1,2,3}.
set -eu
PROPOSER="${1:-${JEPSEN_PROPOSER_NODE:-}}"
MULTIDC=0
if [[ "${JEPSEN_MULTIDC:-0}" == "1" ]] || docker inspect jamoa-multidc-a1 >/dev/null 2>&1; then
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
  CTR="jamoa-multidc-${TARGET}"
else
  if [[ -z "$PROPOSER" ]]; then PROPOSER="n1"; fi
  TARGET="n2"
  if [[ "$PROPOSER" == "n2" ]]; then TARGET="n3"; fi
  if [[ "$PROPOSER" == "n3" ]]; then TARGET="n2"; fi
  CTR="jamoa-jepsen-${TARGET}"
fi

echo "swarm-bounce: kill/start follower $CTR (proposer hint=$PROPOSER multidc=$MULTIDC)"
docker kill "$CTR" || docker stop "$CTR" || true
sleep 3
docker start "$CTR" || true
sleep 2