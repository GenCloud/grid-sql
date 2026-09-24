#!/usr/bin/env bash
# No-nemesis latency baseline (algorithm gate). Still runs register+append consistency checkers.
# Default: no --build. JEPSEN_REBUILD=1 or --rebuild rebuilds once; second Ensure-Cluster only recreates volumes.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
JEPSEN_DIR="$ROOT/benchmarks/jepsen"
cd "$JEPSEN_DIR"
TIME_LIMIT="${JEPSEN_TIME_LIMIT:-30}"
REBUILD=0
for arg in "$@"; do
  if [[ "$arg" == "--rebuild" || "$arg" == "-Rebuild" ]]; then
    REBUILD=1
  fi
done
if [[ "${JEPSEN_REBUILD:-0}" == "1" ]]; then
  REBUILD=1
fi

ensure_cluster() {
  docker compose down -v --remove-orphans || true
  if [[ "$REBUILD" == "1" ]]; then
    echo "Rebuilding images (once)..."
    export DOCKER_BUILDKIT=1
    docker compose up -d --build --force-recreate n1 n2 n3
    REBUILD=0
  else
    echo "Using existing image..."
    docker compose up -d --force-recreate n1 n2 n3
  fi
  sleep 15
}

install_sql_client() {
  echo "Installing grid-sql-client to local Maven repo..."
  (cd "$ROOT" && mvn -B -pl grid-sql-client -am install -DskipTests)
}

run_workload() {
  local workload="$1"
  docker compose --profile control up -d jepsen
  MSYS_NO_PATHCONV=1 docker compose --profile control exec -T \
    -e JAVA_HOME=/opt/java/openjdk \
    -e JAVA_CMD=/opt/java/openjdk/bin/java \
    jepsen sh -c "set -e; export PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; cd /jepsen/jamoa; export JEPSEN_NODES=n1,n2,n3 JEPSEN_HTTP_PORTS=7777,7778,7779 JEPSEN_SQL_PORTS=15432,15433,15434 JEPSEN_SCRIPTS=/jepsen/scripts JEPSEN_USE_LOCALHOST=0; lein run -m jamoa-jepsen.core test --workload ${workload} --time-limit ${TIME_LIMIT} --no-nemesis"
}

install_sql_client
ensure_cluster
echo "=== no-chaos register ==="
run_workload register
ensure_cluster
echo "=== no-chaos append ==="
run_workload append
echo "no-chaos done; collect latency from store/*/history.edn"
