#!/usr/bin/env bash
# Cell O: sealed-miss honesty contour (truncate .sbpt covered in-process by C2 IT).
# Docker cell runs 1dc-chaos with catalog/sealed honesty gate enabled.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export JEPSEN_CATALOG_HONESTY=1
export JEPSEN_SEALED_MISS=1
export STAMP="${STAMP:-$(date +%Y-%m-%d)-jepsen-sealed-miss}"
exec "$SCRIPT_DIR/run-jepsen.sh" "$@"
