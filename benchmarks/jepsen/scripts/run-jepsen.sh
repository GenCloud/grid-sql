#!/usr/bin/env bash
# Full Clojure Jepsen against Compose N=3 (Linux / WSL / CI with lein or Docker control).
# Runs register (Knossos) then append (Elle list-append); stamps PASS/FAIL for both.
set -euo pipefail

# Prefer mvn.cmd on Windows/Git Bash (Unix mvn + Windows JDK breaks classworlds classpath).
run_mvn() {
  if command -v mvn.cmd >/dev/null 2>&1; then
    mvn.cmd "$@"
  else
    mvn "$@"
  fi
}
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
JEPSEN_DIR="$ROOT/benchmarks/jepsen"
cd "$JEPSEN_DIR"

TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
# Set JEPSEN_REBUILD=1 or pass --rebuild to force docker compose --build.
REBUILD=0
for arg in "$@"; do
  if [[ "$arg" == "--rebuild" || "$arg" == "-Rebuild" ]]; then
    REBUILD=1
  fi
done
if [[ "${JEPSEN_REBUILD:-0}" == "1" ]]; then
  REBUILD=1
fi

stamp_outcome() {
  local outcome="$1"
  local notes="$2"
  local STAMP
  STAMP="$(date +%Y-%m-%d)-jepsen-full"
  export STAMP MODE=full-jepsen OUTCOME="$outcome" NOTES="$notes"
  export COMMAND="run-jepsen.sh register+append (time-limit=${TIME_LIMIT})"
  export FULL="$outcome" COMPOSE_STATUS=up CHAOS=not-run
  if [[ -x "$JEPSEN_DIR/scripts/stamp-results.sh" ]]; then
    "$JEPSEN_DIR/scripts/stamp-results.sh" || true
  else
    local GIT
    GIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    {
      echo ""
      echo "### ${STAMP}"
      echo "- mode: full-jepsen"
      echo "- outcome: ${outcome}"
      echo "- git: ${GIT}"
      echo "- compose: up"
      echo "- full-jepsen: ${outcome}"
      echo "- notes: ${notes}"
    } >> "$JEPSEN_DIR/RESULTS.md"
  fi
}

sql_port_open() {
  local port="$1"
  if command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
    return $?
  fi
  # bash /dev/tcp (Git Bash / Linux)
  (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1
}

wait_healthy() {
  local i=0
  while (( i < 60 )); do
    if docker compose ps --format '{{.Health}}' 2>/dev/null | grep -qv healthy; then
      sleep 2
      i=$((i + 1))
      continue
    fi
    # Acceptance path: SQL TCP listen + sticky discovery HTTP (not /jepsen/register).
    if curl -sf "http://127.0.0.1:7777/health/liveness" >/dev/null 2>&1 \
      && sql_port_open 15432 && sql_port_open 15433 && sql_port_open 15434; then
      return 0
    fi
    sleep 2
    i=$((i + 1))
  done
  echo "WARN: cluster health wait timed out; continuing"
  return 0
}

ensure_cluster() {
  echo "Ensuring Compose cluster is up (fresh nodes + clean volumes)..."
  # Named volumes retain OpLog/map across --force-recreate and poison register/append histories.
  docker compose down -v --remove-orphans || true
  if [[ "$REBUILD" == "1" ]]; then
    echo "Rebuilding images (JEPSEN_REBUILD / --rebuild)..."
    export DOCKER_BUILDKIT=1
    docker compose up -d --build --force-recreate n1 n2 n3
  else
    echo "Using existing image (set JEPSEN_REBUILD=1 or --rebuild to rebuild)..."
    docker compose up -d --force-recreate n1 n2 n3
  fi
  wait_healthy
  sleep 5
}

install_sql_client() {
  echo "Installing grid-sql-client to local Maven repo (Jepsen classpath)..."
  (cd "$ROOT" && run_mvn -B -pl grid-sql-client -am install -DskipTests)
}

run_workload() {
  local workload="$1"
  if command -v lein >/dev/null 2>&1; then
    cd "$JEPSEN_DIR/clojure"
    export JEPSEN_USE_LOCALHOST=1
    export JEPSEN_NODES=n1,n2,n3
    export JEPSEN_HTTP_PORTS=7777,7778,7779
    export JEPSEN_SQL_PORTS=15432,15433,15434
    export JEPSEN_SCRIPTS="$JEPSEN_DIR/scripts"
    lein run -m jamoa-jepsen.core test --workload "$workload" --time-limit "$TIME_LIMIT"
    return $?
  fi
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
    echo "ERROR: jepsen control did not become ready (lein bootstrap)"
    return 1
  fi
  MSYS_NO_PATHCONV=1 docker compose --profile control exec -T \
    -e JAVA_HOME=/opt/java/openjdk \
    -e JAVA_CMD=/opt/java/openjdk/bin/java \
    jepsen sh -c "set -e; export PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; cd /jepsen/jamoa; export JEPSEN_NODES=n1,n2,n3 JEPSEN_HTTP_PORTS=7777,7778,7779 JEPSEN_SQL_PORTS=15432,15433,15434 JEPSEN_SCRIPTS=/jepsen/scripts JEPSEN_USE_LOCALHOST=0; java -version; lein run -m jamoa-jepsen.core test --workload ${workload} --time-limit ${TIME_LIMIT}"
}

install_sql_client
ensure_cluster

echo "=== Jepsen workload: register (Knossos) ==="
if run_workload register; then
  REGISTER_OUTCOME=PASS
else
  REGISTER_OUTCOME=FAIL
fi

ensure_cluster

echo "=== Jepsen workload: append (Elle list-append) ==="
if run_workload append; then
  APPEND_OUTCOME=PASS
else
  APPEND_OUTCOME=FAIL
fi

NOTES="register=${REGISTER_OUTCOME}; append=${APPEND_OUTCOME}"
if [[ "$REGISTER_OUTCOME" == "PASS" && "$APPEND_OUTCOME" == "PASS" ]]; then
  stamp_outcome PASS "$NOTES"
  exit 0
fi

stamp_outcome FAIL "$NOTES"
exit 1
