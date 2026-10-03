#!/usr/bin/env bash
# Multi-DC unclean-revive: long writer/voter down without purge + catch-up.
set -euo pipefail
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${MULTIDC_MODE:-async}"
export MULTIDC_FULL="${MULTIDC_FULL:-1}"
export MULTIDC_NEMESIS=1
export JEPSEN_UNCLEAN_REVIVE=1
export JEPSEN_UNCLEAN_DOWN_SEC="${JEPSEN_UNCLEAN_DOWN_SEC:-15}"
export MULTIDC_SKIP_REBUILD="${MULTIDC_SKIP_REBUILD:-0}"
export JEPSEN_TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
export STAMP="${STAMP:-$(date +%Y-%m-%d)-multidc-unclean-revive}"
# Never inherit join/swarm from a prior matrix profile.
unset JEPSEN_JOIN_SHARDS || true
unset JEPSEN_SWARM || true
export MULTIDC_WORKLOADS="${MULTIDC_WORKLOADS:-register,append}"
export MSYS_NO_PATHCONV=1
exec bash "$MULTIDC_DIR/scripts/multidc-common.sh" "$MODE"