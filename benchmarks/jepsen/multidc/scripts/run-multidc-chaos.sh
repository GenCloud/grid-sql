#!/usr/bin/env bash
# Multi-DC Jepsen with nemesis ON (partition + kill-voter + kill/revive DC-A).
# Usage: run-multidc-chaos.sh async|sync-voters
set -euo pipefail
MODE="${1:?mode required: async|sync-voters}"
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
export MULTIDC_FULL="${MULTIDC_FULL:-1}"
export MULTIDC_NEMESIS=1
export MULTIDC_SKIP_REBUILD="${MULTIDC_SKIP_REBUILD:-0}"
export JEPSEN_TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
unset JEPSEN_UNCLEAN_REVIVE || true
export STAMP="${STAMP:-$(date +%Y-%m-%d)-multidc-${MODE}-chaos}"
export MSYS_NO_PATHCONV=1
exec bash "$MULTIDC_DIR/scripts/multidc-common.sh" "$MODE"