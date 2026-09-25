#!/usr/bin/env bash
# Shared Multi-DC runner. Args: MODE (async|sync-voters)
# Scaffold by default; MULTIDC_FULL=1 enables Docker+lein register+append.
set -euo pipefail

# Prefer mvn.cmd on Windows/Git Bash (Unix mvn + Windows JDK breaks classworlds classpath).
run_mvn() {
  if command -v mvn.cmd >/dev/null 2>&1; then
    mvn.cmd "$@"
  else
    mvn "$@"
  fi
}
MODE="${1:?mode required: async|sync-voters}"
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JEPSEN_DIR="$(cd "$MULTIDC_DIR/.." && pwd)"
ROOT="$(cd "$JEPSEN_DIR/../.." && pwd)"
RESULTS="$MULTIDC_DIR/RESULTS.md"
cd "$MULTIDC_DIR"

export MULTIDC_MODE="$MODE"
export JEPSEN_M2="${JEPSEN_M2:-${HOME}/.m2}"
export JEPSEN_NODES="${JEPSEN_NODES:-a1,a2,a3,b1,b2}"
export JEPSEN_HTTP_PORTS="${JEPSEN_HTTP_PORTS:-7777,7778,7779,7780,7781}"
export JEPSEN_SQL_PORTS="${JEPSEN_SQL_PORTS:-15432,15433,15434,15435,15436}"
export JEPSEN_USE_LOCALHOST="${JEPSEN_USE_LOCALHOST:-0}"
export JEPSEN_MULTI_HOST="${JEPSEN_MULTI_HOST:-1}"
TIME_LIMIT="${JEPSEN_TIME_LIMIT:-30}"
SKIP_REBUILD="${MULTIDC_SKIP_REBUILD:-0}"

if [[ "$MODE" == "async" ]]; then MODE_LABEL="ASYNC_SHIP"; else MODE_LABEL="SYNC_VOTERS_ACROSS_DC"; fi

stamp_multidc() {
  local stamp="$1" outcome="$2" notes="$3" reg="${4:--}" app="${5:--}" latency="${6:-}"
  local git date_iso lat_line
  git="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  date_iso="$(date -Iseconds 2>/dev/null || date)"
  lat_line=""
  if [[ -n "$latency" ]]; then lat_line="| latency | $latency |"$'\n'; fi
  cat > "$RESULTS" <<EOF
# Multi-DC Jepsen RESULTS

Stamp template - filled after Multi-DC scaffold validate or full lein register+append.

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | \`$stamp\` |
| date | $date_iso |
| git | $git |
| host | $(hostname 2>/dev/null || echo unknown) |
| mode | $MODE_LABEL ($MODE) |
| outcome | \`$outcome\` |
| register | $reg |
| append | $app |
${lat_line}| notes | $notes |

Multi-host SQL URL:

\`\`\`
grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public
\`\`\`

See [README.md](README.md). Parent 1-DC: [../RESULTS.md](../RESULTS.md).

## History

| stamp | mode | register | append | outcome | notes |
|-------|------|----------|--------|---------|-------|
| \`$stamp\` | $MODE_LABEL | $reg | $app | $outcome | $notes |
EOF
  echo "Wrote $RESULTS stamp=$stamp outcome=$outcome"
}

echo "=== Multi-DC $MODE_LABEL ==="
echo "SQL URL: grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "Docker unavailable"
  stamp_multidc "multidc-$MODE-skip-docker" "SKIP" "Docker daemon unavailable; compose validate and full lein not run."
  exit 0
fi

echo "Validating docker compose config..."
docker compose config >/dev/null
echo "compose config OK (MULTIDC_MODE=$MULTIDC_MODE)"

if [[ "${MULTIDC_FULL:-0}" != "1" ]]; then
  echo "Scaffold only (no cluster up). Set MULTIDC_FULL=1 for Docker+lein register+append."
  stamp_multidc "pending-full-run" "PENDING" "Scaffold compose validate OK ($MODE_LABEL). No 5-node up / lein. Do not claim PASS."
  exit 0
fi

STAMP_BASE="multidc-$MODE-$(date +%Y-%m-%d-%H%M 2>/dev/null || echo run)"
export DOCKER_BUILDKIT=1

if [[ "$SKIP_REBUILD" == "1" ]] && docker images -q jamoa-grid-jepsen:local | grep -q .; then
  echo "Skip rebuild: using existing jamoa-grid-jepsen:local"
else
  "$JEPSEN_DIR/scripts/build-jepsen-image.sh"
fi

echo "Installing grid-sql-client..."
( cd "$ROOT" && run_mvn -B -pl grid-sql-client -am install -DskipTests )

ensure_cluster() {
  if [[ -x "$JEPSEN_DIR/scripts/jepsen-purge.sh" ]]; then
    bash "$JEPSEN_DIR/scripts/jepsen-purge.sh" multidc || true
  else
    docker compose down -v --remove-orphans || true
  fi
  docker compose up -d --force-recreate a1 a2 a3 b1 b2
  echo "Waiting for health..."
  deadline=$((SECONDS + 240))
  ok=0
  while (( SECONDS < deadline )); do
    ok=1
    for name in jamoa-multidc-a1 jamoa-multidc-a2 jamoa-multidc-a3 jamoa-multidc-b1 jamoa-multidc-b2; do
      st=$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null || echo missing)
      [[ "$st" == "healthy" ]] || ok=0
    done
    (( ok == 1 )) && break
    sleep 8
  done
  (( ok == 1 )) || echo "WARN: not fully healthy; continuing"
}

ensure_control() {
  docker compose --profile control up -d jepsen >/dev/null
  deadline=$((SECONDS + 480))
  while (( SECONDS < deadline )); do
    if docker compose --profile control exec -T jepsen bash -lc 'test -f /tmp/jepsen-control-ready && command -v lein && lein version' 2>/dev/null | grep -q Leiningen; then
      return 0
    fi
    echo "control not ready yet"
    sleep 5
  done
  echo "jepsen control not ready"
  return 1
}

run_workload() {
  local workload="$1"
  ensure_control
  local script_name="run-workload-multidc-${workload}.sh"
  cat > "$JEPSEN_DIR/scripts/$script_name" <<EOS
#!/bin/bash
set +e
export PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export JAVA_HOME=/opt/java/openjdk
export JAVA_CMD=/opt/java/openjdk/bin/java
export JVM_OPTS="-Xmx2g -XX:+UseG1GC"
export LEIN_JVM_OPTS="-Xmx1g"
export JAVA_TOOL_OPTIONS="--enable-preview -Xmx2g"
cd /jepsen/jamoa
export JEPSEN_NODES=a1,a2,a3,b1,b2
export JEPSEN_HTTP_PORTS=7777,7778,7779,7780,7781
export JEPSEN_SQL_PORTS=15432,15433,15434,15435,15436
export JEPSEN_SCRIPTS=/jepsen/scripts
export JEPSEN_USE_LOCALHOST=0
export JEPSEN_MULTI_HOST=1
command -v lein
command -v git
lein run -m jamoa-jepsen.core test --workload $workload --time-limit $TIME_LIMIT --no-nemesis
echo LEIN_EXIT=\$?
EOS
  chmod +x "$JEPSEN_DIR/scripts/$script_name"
  local out
  out="$(docker compose --profile control exec -T jepsen bash "/jepsen/scripts/$script_name" 2>&1 || true)"
  echo "$out"
  if echo "$out" | grep -Eq 'Everything looks good|:valid\? true'; then
    return 0
  fi
  local code
  code="$(echo "$out" | sed -n 's/.*LEIN_EXIT=\([0-9][0-9]*\).*/\1/p' | tail -1)"
  return "${code:-1}"
}

ensure_cluster
echo "=== Multi-DC workload: register (Knossos), no-nemesis ==="
REG_OUTCOME=FAIL
if run_workload register; then REG_OUTCOME=PASS; fi

ensure_cluster
echo "=== Multi-DC workload: append (Elle), no-nemesis ==="
APP_OUTCOME=FAIL
if run_workload append; then APP_OUTCOME=PASS; fi

NOTES="FULL lein no-nemesis time-limit=$TIME_LIMIT; register=$REG_OUTCOME; append=$APP_OUTCOME"
if [[ "$REG_OUTCOME" == "PASS" && "$APP_OUTCOME" == "PASS" ]]; then
  stamp_multidc "$STAMP_BASE" "PASS" "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
  exit 0
fi
stamp_multidc "$STAMP_BASE" "FAIL" "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
exit 1