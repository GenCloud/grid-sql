#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPARE="$ROOT/benchmarks/compare"
RESULTS="$ROOT/grid-server-core/benchmarks/lab"
mkdir -p "$RESULTS"
STAMP="${JMH_STAMP:-$(date +%Y-%m-%d)}"
OPS="${COMPARE_OPS:-100}"
ENDPOINT="${ETCD_ENDPOINTS:-http://127.0.0.1:2479}"
ETCDCTL=$(find "$ROOT/.tools/etcd" -name etcdctl -type f 2>/dev/null | head -n1 || true)
if [[ -z "${ETCDCTL}" ]]; then
  echo "etcdctl missing under .tools/etcd" >&2
  exit 1
fi
cd "$COMPARE"
docker compose --profile cluster3 up -d etcd-a etcd-b etcd-c
sleep 8
export ETCDCTL_API=3
TMP=$(mktemp)
for i in $(seq 0 $((OPS-1))); do
  START=$(date +%s%N)
  "$ETCDCTL" --endpoints="$ENDPOINT" put "k3-$i" xxxxxxxx >/dev/null
  END=$(date +%s%N)
  echo $(( (END-START)/1000 )) >> "$TMP"
done
# rough p50 via sort
P50=$(sort -n "$TMP" | awk -v n="$OPS" 'NR==int((n-1)*0.5)+1{print; exit}')
AVG=$(awk '{s+=$1} END{print s/NR}' "$TMP")
rm -f "$TMP"
OUT="$RESULTS/${STAMP}-compare-etcd-3.json"
printf '{"system":"etcd","mode":"3-node-put","endpoints":"%s","ops":%s,"p50Us":%s,"avgUs":%s,"stamp":"%s"}\n' \
  "$ENDPOINT" "$OPS" "$P50" "$AVG" "$STAMP" > "$OUT"
echo "Wrote $OUT"