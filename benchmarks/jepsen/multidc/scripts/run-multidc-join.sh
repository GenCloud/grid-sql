#!/usr/bin/env bash
# Multi-DC join-shards (COVERAGE M): Elle list-append via cross-shard LEFT OUTER JOIN under DC chaos.
# Uses async topology configs (shards=8); JEPSEN_JOIN_SHARDS expands key space.
set -euo pipefail
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
export MULTIDC_FULL="${MULTIDC_FULL:-1}"
export MULTIDC_NEMESIS="${MULTIDC_NEMESIS:-1}"
export MULTIDC_SKIP_REBUILD="${MULTIDC_SKIP_REBUILD:-0}"
export JEPSEN_TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
export JEPSEN_JOIN_SHARDS=1
export JEPSEN_SWARM=""
export MULTIDC_WORKLOADS="${MULTIDC_WORKLOADS:-join}"
unset JEPSEN_UNCLEAN_REVIVE || true
export STAMP="${STAMP:-$(date +%Y-%m-%d)-multidc-async-join-shards}"
export MSYS_NO_PATHCONV=1
exec bash "$MULTIDC_DIR/scripts/multidc-common.sh" async
