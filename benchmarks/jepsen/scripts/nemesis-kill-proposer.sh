#!/usr/bin/env bash
# Kill a proposer selected by Jepsen wire ServerMeta discovery.
# /replication/status is intentionally not used for promotion decisions; HTTP is liveness-only.
set -eu
PROPOSER="${1:-${JEPSEN_PROPOSER_NODE:-}}"

if [[ -z "$PROPOSER" ]]; then
  echo "No wire-discovered proposer supplied; killing n1 as best-effort fallback"
  PROPOSER="n1"
fi

CTR="jamoa-jepsen-${PROPOSER}"
echo "Killing proposer container $CTR"
docker kill "$CTR" || docker stop "$CTR" || true
# Restart so cluster can recover for subsequent ops
sleep 2
docker start "$CTR" || true
