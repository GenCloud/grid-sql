#!/usr/bin/env bash
# Dump Jepsen cluster docker logs for FAIL triage (local + GHA artifacts).
# Usage: dump-jepsen-cluster-logs.sh [out_dir]
# Env: JEPSEN_1DC_CTR_PREFIX, JEPSEN_MDC_CTR_PREFIX, STAMP, JEPSEN_INSTANCE
set -euo pipefail
JEPSEN_DIR="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="${STAMP:-$(date +%Y%m%d-%H%M%S)}"
INST="${JEPSEN_INSTANCE:-}"
OUT_DIR="${1:-$JEPSEN_DIR/cluster-logs/${STAMP}${INST:+-$INST}}"
mkdir -p "$OUT_DIR"

PREFIXES=()
[[ -n "${JEPSEN_1DC_CTR_PREFIX:-}" ]] && PREFIXES+=("$JEPSEN_1DC_CTR_PREFIX")
[[ -n "${JEPSEN_MDC_CTR_PREFIX:-}" ]] && PREFIXES+=("$JEPSEN_MDC_CTR_PREFIX")
# Defaults when env not sourced
PREFIXES+=("jamoa-jepsen" "jamoa-multidc")
# Dedup
declare -A SEEN=()
UNIQUE=()
for p in "${PREFIXES[@]}"; do
  [[ -n "$p" ]] || continue
  [[ -n "${SEEN[$p]:-}" ]] && continue
  SEEN[$p]=1
  UNIQUE+=("$p")
done

echo "dump-jepsen-cluster-logs -> $OUT_DIR"
{
  echo "stamp=$STAMP instance=${INST:-}"
  echo "time=$(date -Iseconds 2>/dev/null || date)"
  echo "prefixes=${UNIQUE[*]}"
  docker ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' 2>/dev/null || true
} >"$OUT_DIR/docker-ps.txt"

dumped=0
for prefix in "${UNIQUE[@]}"; do
  # Match name prefix- (n1/a1/control/...)
  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    safe="$(echo "$name" | tr '/:' '__')"
    out="$OUT_DIR/${safe}.log"
    echo "  docker logs $name -> $out"
    # Full log (no --tail) for FAIL triage; stderr merged
    docker logs --timestamps "$name" >"$out" 2>&1 || true
    # File logger survives SIGKILL better than docker stdout buffers (unclean-p0 gap).
    vol_log="$OUT_DIR/${safe}-grid-visibility.log"
    if docker cp "${name}:/app/data/grid-visibility.log" "$vol_log" 2>/dev/null; then
      echo "  volume log $name -> $vol_log"
    fi
    dumped=$((dumped + 1))
  done < <(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -E "^${prefix}(-|$)" || true)
done

# Also capture compose project leftovers with jamoa- in name if nothing matched
if [[ "$dumped" -eq 0 ]]; then
  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    safe="$(echo "$name" | tr '/:' '__')"
    out="$OUT_DIR/${safe}.log"
    echo "  docker logs $name -> $out"
    docker logs --timestamps "$name" >"$out" 2>&1 || true
    vol_log="$OUT_DIR/${safe}-grid-visibility.log"
    if docker cp "${name}:/app/data/grid-visibility.log" "$vol_log" 2>/dev/null; then
      echo "  volume log $name -> $vol_log"
    fi
    dumped=$((dumped + 1))
  done < <(docker ps -a --format '{{.Names}}' 2>/dev/null | grep -E 'jamoa-(jepsen|multidc)' || true)
fi

# CLASS=harness|elle from caller env (multidc-common.sh FAIL_CLASS). Default unknown.
{
  echo "dumped_containers=$dumped"
  echo "CLASS=${FAIL_CLASS:-unknown}"
  echo "HARNESS_REASON=${HARNESS_REASON:-}"
  echo "stamp=$STAMP"
  # Hint: Created-only containers + no LEIN ??? harness compose timeout (not Elle).
  created_only=0
  if [[ -f "$OUT_DIR/docker-ps.txt" ]]; then
    if grep -Eiq 'Created' "$OUT_DIR/docker-ps.txt" \
      && ! grep -Eiq 'Up |healthy' "$OUT_DIR/docker-ps.txt"; then
      created_only=1
    fi
  fi
  echo "containers_created_only=$created_only"
} | tee "$OUT_DIR/SUMMARY.txt"
echo "DUMP_DIR=$OUT_DIR"
# Marker for GHA / callers
echo "$OUT_DIR" >"$JEPSEN_DIR/cluster-logs/LATEST.txt"
echo "$OUT_DIR" >"$JEPSEN_DIR/CLUSTER_LOGS_DIR.txt"
exit 0
