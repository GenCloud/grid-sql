#!/usr/bin/env bash
# Wipe Jepsen Compose volumes only. Never docker image prune / system prune.
# Corrupt op log length=0 => dirty named volumes, not stale images.
# Always frees host SQL/HTTP binds for the selected scope (matrix cell handoff).
set -euo pipefail
SCOPE="${1:-all}"
PURGE_IMAGES="${PURGE_IMAGES:-0}"
PURGE_M2="${PURGE_M2:-0}"
JEPSEN_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MULTIDC_DIR="$JEPSEN_DIR/multidc"
WITNESS_OVERLAY="$JEPSEN_DIR/witness/docker-compose.witness-overlay.yml"

compose_down() {
  local dir="$1"
  if [[ ! -f "$dir/docker-compose.yml" ]]; then
    echo "WARN: no compose in $dir"
    return 0
  fi
  echo "compose down -v --remove-orphans"
  ( cd "$dir" && docker compose down -v --remove-orphans ) || true
}

witness_overlay_down() {
  if [[ ! -f "$WITNESS_OVERLAY" || ! -f "$MULTIDC_DIR/docker-compose.yml" ]]; then
    return 0
  fi
  echo "compose down (witness overlay)"
  ( cd "$MULTIDC_DIR" && docker compose -f docker-compose.yml -f "$WITNESS_OVERLAY" down -v --remove-orphans ) || true
}

rm_vols() {
  local v
  for v in "$@"; do
    if docker volume rm -f "$v" >/dev/null 2>&1; then
      echo "removed volume $v"
    fi
  done
}

rm_containers() {
  local n
  for n in "$@"; do
    if docker ps -aq --filter "name=$n" 2>/dev/null | grep -q .; then
      echo "force-remove leftover container $n"
      docker rm -f "$n" >/dev/null 2>&1 || true
    fi
  done
}

echo "=== jepsen-purge scope=$SCOPE purgeImages=$PURGE_IMAGES purgeM2=$PURGE_M2 ==="
echo "NOTE: OpLog Corrupt length=0 => wipe Jepsen DATA volumes only; never global docker prune."

if [[ "$SCOPE" == "1dc" || "$SCOPE" == "all" ]]; then
  compose_down "$JEPSEN_DIR"
  rm_containers jamoa-jepsen-n1 jamoa-jepsen-n2 jamoa-jepsen-n3 jamoa-jepsen-jepsen
  rm_vols jepsen_n1-data jepsen_n2-data jepsen_n3-data \
    benchmarks_jepsen_n1-data benchmarks_jepsen_n2-data benchmarks_jepsen_n3-data
fi

if [[ "$SCOPE" == "multidc" || "$SCOPE" == "all" ]]; then
  witness_overlay_down
  compose_down "$MULTIDC_DIR"
  rm_containers jamoa-multidc-a1 jamoa-multidc-a2 jamoa-multidc-a3 \
    jamoa-multidc-b1 jamoa-multidc-b2 jamoa-multidc-w1 jamoa-multidc-control
  rm_vols multidc_a1-data multidc_a2-data multidc_a3-data multidc_b1-data multidc_b2-data \
    multidc_w1-data w1-data
  if [[ "$PURGE_M2" == "1" ]]; then
    rm_vols multidc_multidc-jepsen-m2 multidc_multidc-jepsen-work
  fi
fi

if [[ "$PURGE_IMAGES" == "1" ]]; then
  echo "Removing only jamoa-grid-jepsen images except :local (no docker image prune)..."
  while read -r id repo; do
    [[ -z "${id:-}" ]] && continue
    if [[ "$repo" == "jamoa-grid-jepsen:local" ]]; then
      echo "keep $repo"
      continue
    fi
    echo "removing Jepsen image $repo ($id)"
    docker rmi -f "$id" >/dev/null 2>&1 || true
  done < <(docker images jamoa-grid-jepsen --format '{{.ID}} {{.Repository}}:{{.Tag}}' 2>/dev/null || true)
fi

echo "jepsen-purge done"
