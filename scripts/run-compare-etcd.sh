#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPARE="$ROOT/benchmarks/compare"
RESULTS="$ROOT/grid-server-core/benchmarks/lab"
mkdir -p "$RESULTS"
STAMP="${JMH_STAMP:-$(date +%Y-%m-%d)}"
OPS="${COMPARE_OPS:-500}"
ENDPOINT="${ETCD_ENDPOINTS:-http://127.0.0.1:2379}"

cd "$COMPARE"
docker compose up -d etcd1
sleep 3

tmp="$(mktemp)"
value="xxxxxxxx"
for i in $(seq 0 $((OPS - 1))); do
  start=$(date +%s%N)
  docker exec jamoa-etcd1 etcdctl --endpoints=http://127.0.0.1:2379 put "k$i" "$value" >/dev/null
  end=$(date +%s%N)
  echo $(( (end - start) / 1000 )) >> "$tmp"
done

python3 - "$tmp" "$RESULTS/$STAMP-compare-etcd.json" "$STAMP" "$OPS" "$ENDPOINT" <<'PY'
import json, sys, statistics
path, out, stamp, ops, endpoint = sys.argv[1:6]
vals = sorted(int(x) for x in open(path) if x.strip())
def pct(p):
    return vals[int((len(vals)-1)*p)]
doc = {
  "system": "etcd",
  "mode": "1-node-put",
  "endpoints": endpoint,
  "ops": int(ops),
  "payloadBytes": 8,
  "avgUs": round(statistics.mean(vals), 3),
  "p50Us": pct(0.50),
  "p99Us": pct(0.99),
  "stamp": stamp,
}
open(out,"w").write(json.dumps(doc, indent=2))
print(json.dumps(doc, indent=2))
PY
rm -f "$tmp"
