#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPARE="$ROOT/benchmarks/compare"
STARTER="$ROOT/grid-server-core"
RESULTS="$STARTER/benchmarks/lab"
mkdir -p "$RESULTS"
STAMP="${JMH_STAMP:-$(date +%Y-%m-%d)}"
OPS="${COMPARE_OPS:-200}"
cd "$COMPARE"
docker compose up -d redis redis-replica
sleep 3
export JMH_STAMP="$STAMP"
export COMPARE_OPS="$OPS"
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export REDIS_REPLICA_HOST=127.0.0.1
export REDIS_REPLICA_PORT=6380
export COMPARE_RESULTS_DIR="$RESULTS"
cd "$STARTER"
mvn -q -Dtest=RedisJedisCompareHarness test
echo "Redis compare via host Jedis complete (stamp=$STAMP)"