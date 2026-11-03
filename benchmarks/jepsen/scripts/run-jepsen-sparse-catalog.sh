#!/usr/bin/env bash
# Cell P: sparse-catalog / AUTH+DML under catalog apply lag — honesty fail on schema skew.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export JEPSEN_CATALOG_HONESTY=1
export JEPSEN_SPARSE_CATALOG=1
export STAMP="${STAMP:-$(date +%Y-%m-%d)-jepsen-sparse-catalog}"
exec "$SCRIPT_DIR/run-jepsen.sh" "$@"
