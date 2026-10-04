#!/usr/bin/env bash
# Jepsen 1-DC join-shards (COVERAGE K): Elle list-append via cross-shard LEFT OUTER JOIN under chaos.
# GHA: JEPSEN_REBUILD=0 (shared image).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
JEPSEN_DIR="$ROOT/benchmarks/jepsen"
# shellcheck source=jepsen-instance-env.sh
. "$JEPSEN_DIR/scripts/jepsen-instance-env.sh"
cd "$JEPSEN_DIR"

TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
REBUILD=0
for arg in "$@"; do
  if [[ "$arg" == "--rebuild" || "$arg" == "-Rebuild" ]]; then
    REBUILD=1
  fi
done
if [[ "${JEPSEN_REBUILD:-0}" == "1" ]]; then
  REBUILD=1
fi

export JEPSEN_JOIN_SHARDS=1
unset JEPSEN_SWARM || true
unset JEPSEN_UNCLEAN_REVIVE || true
unset JEPSEN_MULTIDC || true

run_mvn() {
  if command -v mvn.cmd >/dev/null 2>&1; then
    mvn.cmd "$@"
  else
    mvn "$@"
  fi
}

stamp_outcome() {
  local outcome="$1"
  local notes="$2"
  export STAMP="${STAMP:-$(date +%Y-%m-%d)-jepsen-join-shards}"
  export MODE=1dc-join-shards-chaos OUTCOME="$outcome" NOTES="$notes"
  export COMMAND="run-jepsen-join.sh join (time-limit=${TIME_LIMIT})"
  export FULL="$outcome" COMPOSE_STATUS=up CHAOS="partition+kill"
  if [[ -x "$JEPSEN_DIR/scripts/stamp-results.sh" ]]; then
    "$JEPSEN_DIR/scripts/stamp-results.sh" || true
  fi
}

ensure_cluster() {
  echo "Ensuring Compose cluster (fresh volumes)..."
  docker compose down -v --remove-orphans || true
  if [[ "$REBUILD" == "1" ]]; then
    echo "Rebuilding images..."
    export DOCKER_BUILDKIT=1
    docker compose up -d --build --force-recreate n1 n2 n3
    REBUILD=0
  else
    echo "Using existing jamoa-grid-jepsen:local..."
    docker compose up -d --force-recreate n1 n2 n3
  fi
  sleep "${JEPSEN_SETTLE_SEC:-12}"
}

ensure_control() {
  docker compose --profile control up -d jepsen
  local ready=0
  local i=0
  while (( i < 90 )); do
    if MSYS_NO_PATHCONV=1 docker compose --profile control exec -T jepsen \
        test -f /tmp/jepsen-control-ready >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 2
    i=$((i + 1))
  done
  if [[ "$ready" != "1" ]]; then
    echo "ERROR: jepsen control did not become ready" >&2
    return 1
  fi
}

run_join() {
  ensure_control
  local script_name="run-workload-chaos-join.sh"
  cat > "$JEPSEN_DIR/scripts/$script_name" <<EOF
#!/bin/bash
set +e
export PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export JAVA_HOME=/opt/java/openjdk JAVA_CMD=/opt/java/openjdk/bin/java
export JVM_OPTS="-Xmx2g -XX:+UseG1GC" LEIN_JVM_OPTS="-Xmx1g"
export JAVA_TOOL_OPTIONS="--enable-preview -Xmx2g"
cd /jepsen/jamoa
export JEPSEN_NODES=n1,n2,n3 JEPSEN_HTTP_PORTS=7777,7778,7779 JEPSEN_SQL_PORTS=15432,15433,15434
export JEPSEN_SCRIPTS=/jepsen/scripts JEPSEN_USE_LOCALHOST=0
export JEPSEN_JOIN_SHARDS=1
pkill -9 -f 'jamoa-jepsen.core' 2>/dev/null || true
sleep 1
lein run -m jamoa-jepsen.core test --workload join --time-limit ${TIME_LIMIT}
echo LEIN_EXIT=$?
EOF
  chmod +x "$JEPSEN_DIR/scripts/$script_name"
  set +e
  local out
  out="$(MSYS_NO_PATHCONV=1 docker compose --profile control exec -T jepsen bash "/jepsen/scripts/$script_name" 2>&1)"
  local code=$?
  set -e
  printf '%s\n' "$out"
  local tmp
  tmp="$(mktemp)"
  printf '%s\n' "$out" >"$tmp"
  if [[ -x "$JEPSEN_DIR/scripts/jepsen-honesty-gate.sh" ]]; then
    local honesty
    honesty="$("$JEPSEN_DIR/scripts/jepsen-honesty-gate.sh" "$tmp" 2>&1 || true)"
    echo "$honesty"
    if echo "$honesty" | grep -Fq 'HONESTY_FAIL='; then
      rm -f "$tmp"
      return 1
    fi
  fi
  rm -f "$tmp"
  if printf '%s' "$out" | grep -q 'Analysis invalid'; then return 1; fi
  if printf '%s' "$out" | grep -q 'Everything looks good'; then return 0; fi
  if printf '%s' "$out" | grep -Eq '^ :valid\? true'; then return 0; fi
  local lein_exit
  lein_exit="$(printf '%s' "$out" | sed -n 's/^LEIN_EXIT=\([0-9]\+\)/\1/p' | tail -1)"
  if [[ -n "$lein_exit" ]]; then return "$lein_exit"; fi
  return "$code"
}



dump_cluster_logs_on_fail() {
  local ec="${1:-1}"
  if [[ "$ec" -eq 0 ]]; then
    return 0
  fi
  echo "=== FAIL (exit=$ec): dumping cluster docker logs before purge ==="
  if [[ -x "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" ]]; then
    bash "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" || true
  fi
}

release_ports() {
  local _ec=$?
  dump_cluster_logs_on_fail "$_ec" || true
  unset JEPSEN_JOIN_SHARDS || true
  if [[ -x "$JEPSEN_DIR/scripts/jepsen-purge.sh" ]]; then
    bash "$JEPSEN_DIR/scripts/jepsen-purge.sh" 1dc || true
  else
    docker compose down -v --remove-orphans || true
  fi
}
trap release_ports EXIT

if [[ "${JEPSEN_SKIP_MVN_INSTALL:-0}" == "1" ]]; then
  echo "Skip per-cell mvn install (JEPSEN_SKIP_MVN_INSTALL=1; use shared ~/.m2)"
else
  echo "Installing grid-sql-client..."
  (cd "$ROOT" && run_mvn -B -pl grid-sql-client -am install -DskipTests)
fi

ensure_cluster
echo "=== Jepsen JOIN/shards: Elle via cross-shard JOIN read ==="
set +e
run_join
code=$?
set -e
if [[ "$code" -eq 0 ]]; then
  stamp_outcome PASS "join-shards=PASS"
  exit 0
fi
stamp_outcome FAIL "join-shards=FAIL"
dump_cluster_logs_on_fail 1 || true
exit 1
