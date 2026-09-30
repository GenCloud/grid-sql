#!/usr/bin/env bash
# Shared Multi-DC runner. Args: MODE (async|sync-voters)
# MULTIDC_FULL=1 required for Docker+lein. MULTIDC_NEMESIS=1 (default) enables nemesis;
# MULTIDC_NEMESIS=0 runs --no-nemesis. JEPSEN_UNCLEAN_REVIVE=1 selects unclean kill path.
set -euo pipefail

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
# Default ON for chaos wrappers; nochao wrappers set MULTIDC_NEMESIS=0.
MULTIDC_NEMESIS="${MULTIDC_NEMESIS:-1}"

if [[ "$MODE" == "async" ]]; then MODE_LABEL="ASYNC_SHIP"; else MODE_LABEL="SYNC_VOTERS_ACROSS_DC"; fi
if [[ "$MULTIDC_NEMESIS" == "1" ]]; then CHAOS_NOTE="dc-link+kill-voter+kill-dc-a+revive-dc-a"; else CHAOS_NOTE="no-nemesis"; fi
if [[ "${JEPSEN_UNCLEAN_REVIVE:-0}" == "1" ]]; then CHAOS_NOTE="unclean-revive+${CHAOS_NOTE}"; fi

stamp_multidc() {
  local stamp="$1" outcome="$2" notes="$3" reg="${4:--}" app="${5:--}" latency="${6:-}"
  local git date_iso lat_line
  git="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  date_iso="$(date -Iseconds 2>/dev/null || date)"
  lat_line=""
  if [[ -n "$latency" ]]; then lat_line="| latency | $latency |"$'\n'; fi
  cat > "$RESULTS" <<EOF
# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent \`:valid? true\`).

## Latest stamp ($MODE_LABEL)

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
| chaos | $CHAOS_NOTE |
${lat_line}| notes | $notes |

Multi-host SQL URL:

\`\`\`
grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public
\`\`\`

See [README.md](README.md). Coverage: [../COVERAGE.md](../COVERAGE.md). Parent 1-DC: [../RESULTS.md](../RESULTS.md).

## History

| stamp | mode | register | append | outcome | notes |
|-------|------|----------|--------|---------|-------|
| \`$stamp\` | $MODE_LABEL | $reg | $app | $outcome | $notes |
EOF
  echo "Wrote $RESULTS stamp=$stamp outcome=$outcome register=$reg append=$app chaos=$CHAOS_NOTE"
}

echo "=== Multi-DC $MODE_LABEL (nemesis=$MULTIDC_NEMESIS) ==="
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

STAMP_BASE="${STAMP:-multidc-$MODE-$(date +%Y-%m-%d-%H%M 2>/dev/null || echo run)}"
export DOCKER_BUILDKIT=1

if [[ "$SKIP_REBUILD" == "1" ]] && docker images -q jamoa-grid-jepsen:local | grep -q .; then
  echo "Skip rebuild: using existing jamoa-grid-jepsen:local"
else
  "$JEPSEN_DIR/scripts/build-jepsen-image.sh"
fi

echo "Installing grid-sql-client..."
( cd "$ROOT" && run_mvn -B -pl grid-sql-client -am install -DskipTests )

ensure_cluster() {
  # Scope=all frees leftover 1-DC host binds (15432+) before Multi-DC up.
  if [[ -x "$JEPSEN_DIR/scripts/jepsen-purge.sh" ]]; then
    bash "$JEPSEN_DIR/scripts/jepsen-purge.sh" all || true
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

CONTROL_NAME="${CONTROL_NAME:-jamoa-multidc-control}"

# Git Bash converts /jepsen/... to a host path; keep container paths literal.
export MSYS_NO_PATHCONV=1

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
  # Windows Docker: `cp dir/. dest/` nests oddly; copy dir then rename.
  docker cp "$(docker_host_path "$JEPSEN_DIR/scripts")" "${CONTROL_NAME}:/jepsen/scripts-new"
  docker exec "$CONTROL_NAME" bash -lc 'rm -rf /jepsen/scripts; mv /jepsen/scripts-new /jepsen/scripts'
  local m2_genfork="${JEPSEN_M2}/repository/org/genfork"
  if [[ ! -d "$m2_genfork/grid-sql-client" ]]; then
    echo "ERROR: host m2 missing org.genfork/grid-sql-client at $m2_genfork" >&2
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
      # Always re-sync host scripts/sources (control may already be up from a prior cell).
      sync_control_workspace
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
  local nem_arg=""
  if [[ "$MULTIDC_NEMESIS" != "1" ]]; then
    nem_arg="nochao"
  fi
  local out
  # MSYS_NO_PATHCONV: keep /jepsen/... paths inside the container.
  out="$(MSYS_NO_PATHCONV=1 docker compose --profile control exec -T \
    -e "MULTIDC_MODE=$MULTIDC_MODE" \
    -e "JEPSEN_UNCLEAN_REVIVE=${JEPSEN_UNCLEAN_REVIVE:-0}" \
    -e "JEPSEN_UNCLEAN_DOWN_SEC=${JEPSEN_UNCLEAN_DOWN_SEC:-15}" \
    jepsen bash /jepsen/scripts/run-workload-multidc.sh "$workload" "$TIME_LIMIT" $nem_arg 2>&1 || true)"
  echo "$out"
  # Authoritative success: Jepsen banner and/or LEIN_EXIT=0.
  # Do NOT require absence of nested ":valid? false" — analyzer trees / prior
  # lines can contain that substring while the final result is valid (GHA false FAIL).
  if echo "$out" | grep -Fq 'Everything looks good'; then
    return 0
  fi
  if echo "$out" | grep -Eq 'Analysis invalid'; then
    return 1
  fi
  local code
  code="$(echo "$out" | sed -n 's/^LEIN_EXIT=\([0-9][0-9]*\)$/\1/p' | tail -1)"
  if [[ -z "$code" ]]; then
    code="$(echo "$out" | sed -n 's/.*LEIN_EXIT=\([0-9][0-9]*\).*/\1/p' | tail -1)"
  fi
  if [[ "$code" == "0" ]]; then
    return 0
  fi
  # Final pretty-print line only (leading space), not nested :timeline {:valid? true}.
  if echo "$out" | grep -Eq '(^|[[:space:]]) :valid\? false([\}[:space:]]|$)'; then
    return 1
  fi
  if echo "$out" | grep -Eq 'jepsen\.core \{.*:valid\? true\}'; then
    return 0
  fi
  return "${code:-1}"
}

ensure_cluster
release_jepsen_ports() {
  echo "Releasing Jepsen host ports (1dc+multidc compose down)..."
  if [[ -x "$JEPSEN_DIR/scripts/jepsen-purge.sh" ]]; then
    bash "$JEPSEN_DIR/scripts/jepsen-purge.sh" all || true
  else
    docker compose down -v --remove-orphans || true
  fi
}
trap release_jepsen_ports EXIT

echo "=== Multi-DC workload: register (Knossos), nemesis=$MULTIDC_NEMESIS ==="
REG_OUTCOME=FAIL
if run_workload register; then REG_OUTCOME=PASS; fi

ensure_cluster
echo "=== Multi-DC workload: append (Elle), nemesis=$MULTIDC_NEMESIS ==="
APP_OUTCOME=FAIL
if run_workload append; then APP_OUTCOME=PASS; fi

NOTES="FULL lein chaos=$CHAOS_NOTE time-limit=$TIME_LIMIT; register=$REG_OUTCOME; append=$APP_OUTCOME"
echo "=== Multi-DC outcomes register=$REG_OUTCOME append=$APP_OUTCOME ==="
if [[ "$REG_OUTCOME" == "PASS" && "$APP_OUTCOME" == "PASS" ]]; then
  stamp_multidc "$STAMP_BASE" "PASS" "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
  exit 0
fi
stamp_multidc "$STAMP_BASE" "FAIL" "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
exit 1