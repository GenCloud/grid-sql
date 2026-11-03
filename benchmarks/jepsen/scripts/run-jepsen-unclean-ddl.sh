#!/usr/bin/env bash
# Cell N: unclean revive + DDL/catalog honesty (no mid-run purge).
# Delegates to unclean-revive; catalog epoch probe via honesty gate env.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export JEPSEN_CATALOG_HONESTY=1
export STAMP="${STAMP:-$(date +%Y-%m-%d)-jepsen-unclean-ddl}"
exec "$SCRIPT_DIR/run-jepsen-unclean-revive.sh" "$@"
