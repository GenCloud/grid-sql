#!/usr/bin/env bash
# Multi-DC ASYNC_SHIP + AdaptiveReplicaSwarm (COVERAGE L): append + swarm-bounce nemesis.
# Configs: multidc/configs/async-swarm/ (baked into jamoa-grid-jepsen:local).
set -euo pipefail
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
export MULTIDC_FULL="${MULTIDC_FULL:-1}"
export MULTIDC_NEMESIS="${MULTIDC_NEMESIS:-1}"
export MULTIDC_SKIP_REBUILD="${MULTIDC_SKIP_REBUILD:-0}"
export JEPSEN_TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
export JEPSEN_SWARM=1
export JEPSEN_JOIN_SHARDS=""
export MULTIDC_WORKLOADS="${MULTIDC_WORKLOADS:-append}"
unset JEPSEN_UNCLEAN_REVIVE || true
export STAMP="${STAMP:-$(date +%Y-%m-%d)-multidc-async-swarm-chaos}"
export MSYS_NO_PATHCONV=1
exec bash "$MULTIDC_DIR/scripts/multidc-common.sh" async-swarm
