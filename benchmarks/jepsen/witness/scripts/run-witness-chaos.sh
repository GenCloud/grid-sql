#!/usr/bin/env bash
# Multi-DC + Witness (w1) Jepsen chaos stamp (TD-HA-001). Bash-only for CI review.
# Honest PASS/FAIL in benchmarks/jepsen/witness/RESULTS.md — never invent :valid? true.
set -euo pipefail

run_mvn() {
  if command -v mvn.cmd >/dev/null 2>&1; then
    mvn.cmd "$@"
  else
    mvn "$@"
  fi
}

WITNESS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MULTIDC_DIR="$(cd "$WITNESS_DIR/../multidc" && pwd)"
JEPSEN_DIR="$(cd "$WITNESS_DIR/.." && pwd)"
ROOT="$(cd "$JEPSEN_DIR/../.." && pwd)"
RESULTS="$WITNESS_DIR/RESULTS.md"
# Relative to MULTIDC_DIR so Docker Desktop does not mangle /d/... → D:\d\...
OVERLAY_REL="../witness/docker-compose.witness-overlay.yml"
TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"
SKIP_REBUILD="${MULTIDC_SKIP_REBUILD:-0}"
NO_NEMESIS="${JEPSEN_NO_NEMESIS:-0}"
CONTROL_NAME="${CONTROL_NAME:-jamoa-multidc-control}"
export JEPSEN_M2="${JEPSEN_M2:-${HOME}/.m2}"
export MULTIDC_MODE=async
export JEPSEN_WITNESS=1
export DOCKER_BUILDKIT=1
export MSYS_NO_PATHCONV=1
GRID_URL="grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public"
STAMP="${STAMP:-$(date +%Y-%m-%d)-witness-chaos}"
cd "$MULTIDC_DIR"

stamp_witness() {
  local outcome="$1" notes="$2" reg="${3:--}" app="${4:--}" lat="${5:-n/a}"
  local git date_iso chaos_note
  git="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  date_iso="$(date -Iseconds 2>/dev/null || date)"
  if [[ "$NO_NEMESIS" == "1" ]]; then chaos_note="no-nemesis"; else chaos_note="dc-link+kill-dc-a+revive (Witness overlay)"; fi
  cat > "$RESULTS" <<EOF
# Witness Jepsen RESULTS

Honest PASS/FAIL after Docker+lein Witness overlay (never invent \`:valid? true\`).

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | \`$STAMP\` |
| date | $date_iso |
| git | $git |
| host | $(hostname 2>/dev/null || echo unknown) |
| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |
| outcome | \`$outcome\` |
| register | $reg |
| append | $app |
| chaos | $chaos_note |
| multi-host SQL | \`$GRID_URL\` |
| health | \`/health/liveness\` (Actuator base-path \`/\`) |
| latency (ok-ops, warmup 10s) | $lat |
| notes | $notes |

## History

| stamp | register | append | outcome | notes |
|-------|----------|--------|---------|-------|
| \`$STAMP\` | $reg | $app | $outcome | $notes |

Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Coverage: [../COVERAGE.md](../COVERAGE.md). Debt: TD-HA-001.
EOF
  echo "Wrote $RESULTS stamp=$STAMP outcome=$outcome"
}

compose() {
  docker compose -f docker-compose.yml -f "$OVERLAY_REL" "$@"
}

ensure_image() {
  if ! docker image inspect jamoa-grid-jepsen:local >/dev/null 2>&1; then
    if [[ "$SKIP_REBUILD" == "1" ]]; then
      echo "ERROR: image missing and MULTIDC_SKIP_REBUILD=1" >&2
      return 1
    fi
    bash "$JEPSEN_DIR/scripts/build-jepsen-image.sh"
  fi
  echo "Overlaying multidc+witness configs onto jamoa-grid-jepsen:local..."
  (cd "$ROOT" && docker build -f benchmarks/jepsen/Dockerfile.witness-overlay -t jamoa-grid-jepsen:local .)
}

ensure_cluster() {
  ensure_image
  if [[ -x "$JEPSEN_DIR/scripts/jepsen-purge.sh" ]]; then
    bash "$JEPSEN_DIR/scripts/jepsen-purge.sh" multidc || true
  fi
  compose stop a1 a2 a3 b1 b2 w1 2>/dev/null || true
  compose rm -f a1 a2 a3 b1 b2 w1 2>/dev/null || true
  for v in multidc_a1-data multidc_a2-data multidc_a3-data multidc_b1-data multidc_b2-data multidc_w1-data w1-data; do
    docker volume rm -f "$v" 2>/dev/null || true
  done
  compose up -d --force-recreate a1 a2 a3 b1 b2 w1
  echo "Waiting for health..."
  deadline=$((SECONDS + 360))
  ok=0
  while (( SECONDS < deadline )); do
    ok=1
    for name in jamoa-multidc-a1 jamoa-multidc-a2 jamoa-multidc-a3 jamoa-multidc-b1 jamoa-multidc-b2 jamoa-multidc-w1; do
      st=$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null || echo missing)
      [[ "$st" == "healthy" ]] || ok=0
    done
    (( ok == 1 )) && break
    sleep 8
  done
  (( ok == 1 )) || echo "WARN: not fully healthy; continuing"
}

# Docker Desktop on Windows needs drive-letter paths for `docker cp` (not /d/...).
docker_host_path() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$p"
    return
  fi
  if [[ "$p" =~ ^/([a-zA-Z])/(.*)$ ]]; then
    local drive="${BASH_REMATCH[1]}"
    local rest="${BASH_REMATCH[2]}"
    echo "${drive^^}:/${rest}"
    return
  fi
  echo "$p"
}

sync_control_workspace() {
  echo "docker cp clojure + scripts + grid-sql-client into control..."
  docker exec "$CONTROL_NAME" bash -lc \
    'mkdir -p /jepsen/jamoa/store /root/.m2/repository/org/genfork; rm -rf /jepsen/jamoa/src /jepsen/jamoa/project.clj /jepsen/scripts /jepsen/scripts-new'
  docker cp "$(docker_host_path "$JEPSEN_DIR/clojure/project.clj")" "${CONTROL_NAME}:/jepsen/jamoa/project.clj"
  docker cp "$(docker_host_path "$JEPSEN_DIR/clojure/src")" "${CONTROL_NAME}:/jepsen/jamoa/src"
  docker cp "$(docker_host_path "$JEPSEN_DIR/scripts")" "${CONTROL_NAME}:/jepsen/scripts-new"
  docker exec "$CONTROL_NAME" bash -lc 'rm -rf /jepsen/scripts; mv /jepsen/scripts-new /jepsen/scripts'
  local m2_genfork="${JEPSEN_M2}/repository/org/genfork"
  if [[ ! -d "$m2_genfork/grid-sql-client" ]]; then
    echo "ERROR: host m2 missing org.genfork/grid-sql-client" >&2
    return 1
  fi
  docker exec "$CONTROL_NAME" bash -lc 'rm -rf /root/.m2/repository/org/genfork'
  docker cp "$(docker_host_path "$m2_genfork")" "${CONTROL_NAME}:/root/.m2/repository/org/genfork"
  docker exec "$CONTROL_NAME" bash -lc 'test -f /jepsen/scripts/run-workload-multidc.sh'
}

ensure_control() {
  docker compose --profile control up -d jepsen >/dev/null
  deadline=$((SECONDS + 480))
  while (( SECONDS < deadline )); do
    if docker compose --profile control exec -T jepsen bash -lc 'test -f /tmp/jepsen-control-ready && command -v lein && lein version' 2>/dev/null | grep -q Leiningen; then
      sync_control_workspace
      return 0
    fi
    echo "control not ready yet"
    sleep 5
  done
  echo "jepsen control not ready" >&2
  return 1
}

run_workload() {
  local workload="$1"
  ensure_control
  local nem_arg=""
  if [[ "$NO_NEMESIS" == "1" ]]; then nem_arg="nochao"; fi
  local out
  out="$(docker compose --profile control exec -T \
    -e "MULTIDC_MODE=async" \
    -e "JEPSEN_WITNESS=1" \
    jepsen bash /jepsen/scripts/run-workload-multidc.sh "$workload" "$TIME_LIMIT" $nem_arg 2>&1 || true)"
  echo "$out"
  if echo "$out" | grep -Eq 'Everything looks good|:valid\? true'; then
    if echo "$out" | grep -Eq ':valid\? false|Analysis invalid'; then
      return 1
    fi
    return 0
  fi
  local code
  code="$(echo "$out" | sed -n 's/.*LEIN_EXIT=\([0-9][0-9]*\).*/\1/p' | tail -1)"
  return "${code:-1}"
}

echo "=== Witness chaos (TD-HA-001) ==="
echo "SQL URL: $GRID_URL"
compose config >/dev/null
echo "compose config OK (Witness overlay)"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  stamp_witness FAIL "BLOCKED: docker daemon unavailable"
  exit 2
fi

echo "Installing grid-sql-client..."
(cd "$ROOT" && run_mvn -B -pl grid-sql-client -am install -DskipTests)

ensure_cluster
echo "=== Witness workload: register ==="
REG_OUTCOME=FAIL
if run_workload register; then REG_OUTCOME="PASS (:valid? true)"; else REG_OUTCOME=FAIL
  if [[ -f "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" ]]; then
    echo "=== FAIL register: dumping cluster logs before recreate ==="
    bash "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" || true
  fi
fi

ensure_cluster
echo "=== Witness workload: append ==="
APP_OUTCOME=FAIL
if run_workload append; then APP_OUTCOME="PASS (:valid? true)"; else APP_OUTCOME=FAIL; fi

NOTES="register=$REG_OUTCOME; append=$APP_OUTCOME; time-limit=$TIME_LIMIT; Witness w1 overlay"
if [[ "$REG_OUTCOME" == PASS* && "$APP_OUTCOME" == PASS* ]]; then
  stamp_witness PASS "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
  exit 0
fi
stamp_witness FAIL "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
if [[ -f "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" ]]; then
  echo "=== FAIL: dumping cluster docker logs before purge ==="
  bash "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" || true
fi
exit 1