#!/usr/bin/env bash
# Isolate or heal a Compose node on jamoa-jepsen-net.
# Usage: nemesis-partition.sh isolate n3 | nemesis-partition.sh heal
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ACTION="${1:-}"
TARGET="${2:-n3}"
NET="jamoa-jepsen-net"
CTR="jamoa-jepsen-${TARGET}"

case "$ACTION" in
  isolate)
    echo "Disconnect $CTR from $NET"
    docker network disconnect -f "$NET" "$CTR" || true
    ;;
  heal)
    echo "Reconnect all nodes to $NET"
    for n in n1 n2 n3; do
      c="jamoa-jepsen-${n}"
      docker network connect "$NET" "$c" 2>/dev/null || true
    done
    ;;
  *)
    echo "Usage: $0 isolate <n1|n2|n3> | $0 heal" >&2
    exit 2
    ;;
esac
