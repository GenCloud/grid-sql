#!/usr/bin/env bash
# Multi-DC chaos: partition / heal DC-link, kill remote voter b1, or kill/revive whole DC-A.
# Usage: nemesis-dc-link.sh isolate | heal | kill-voter | kill-dc-a | revive-dc-a
set -eu
ACTION="${1:-}"
NET="jamoa-multidc-dc-link"
B1="jamoa-multidc-b1"
B2="jamoa-multidc-b2"
A1="jamoa-multidc-a1"
A2="jamoa-multidc-a2"
A3="jamoa-multidc-a3"

case "$ACTION" in
  isolate)
    echo "Disconnect $B1 $B2 from $NET (DC-link partition)"
    docker network disconnect -f "$NET" "$B1" || true
    docker network disconnect -f "$NET" "$B2" || true
    ;;
  heal)
    echo "Reconnect $B1 $B2 to $NET"
    docker network connect "$NET" "$B1" 2>/dev/null || true
    docker network connect "$NET" "$B2" 2>/dev/null || true
    ;;
  kill-voter)
    if [[ "${MULTIDC_MODE:-async}" == "async" ]]; then
      echo "ASYNC: skip kill-voter (dc-link partition only)"
    else
      echo "Kill remote voter $B1 then restart"
      docker kill "$B1" || docker stop "$B1" || true
      sleep 2
      docker start "$B1" || true
    fi
    ;;
  kill-dc-a)
    echo "Kill whole DC-A ($A1 $A2 $A3); leave down for Hold claim"
    for c in "$A1" "$A2" "$A3"; do
      docker kill "$c" || docker stop "$c" || true
    done
    ;;
  revive-dc-a)
    echo "Revive DC-A ($A1 $A2 $A3) for epoch fence test"
    for c in "$A1" "$A2" "$A3"; do
      docker start "$c" || true
    done
    ;;
  *)
    echo "Usage: $0 isolate | heal | kill-voter | kill-dc-a | revive-dc-a" >&2
    exit 2
    ;;
esac