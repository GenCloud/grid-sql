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
MODE="${1:?mode required: async|sync-voters|async-swarm}"
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
# Comma-separated Jepsen workloads (default register+append). Edge: append | join.
MULTIDC_WORKLOADS="${MULTIDC_WORKLOADS:-register,append}"
TIME_LIMIT="${JEPSEN_TIME_LIMIT:-30}"
SKIP_REBUILD="${MULTIDC_SKIP_REBUILD:-0}"
# Default ON for chaos wrappers; nochao wrappers set MULTIDC_NEMESIS=0.
MULTIDC_NEMESIS="${MULTIDC_NEMESIS:-1}"

case "$MODE" in
  async) MODE_LABEL="ASYNC_SHIP" ;;
  sync-voters) MODE_LABEL="SYNC_VOTERS_ACROSS_DC" ;;
  async-swarm) MODE_LABEL="ASYNC_SHIP+SWARM" ;;
  *)
    echo "ERROR: unknown MULTIDC_MODE=$MODE (async|sync-voters|async-swarm)" >&2
    exit 2
    ;;
esac
if [[ "$MULTIDC_NEMESIS" == "1" ]]; then
  if [[ "${JEPSEN_SWARM:-}" == "1" ]]; then
    CHAOS_NOTE="swarm-bounce+dc-link+kill-voter"
  else
    CHAOS_NOTE="dc-link+kill-voter+kill-dc-a+revive-dc-a"
  fi
else
  CHAOS_NOTE="no-nemesis"
fi
if [[ "${JEPSEN_UNCLEAN_REVIVE:-0}" == "1" ]]; then CHAOS_NOTE="unclean-revive+${CHAOS_NOTE}"; fi
if [[ "${JEPSEN_JOIN_SHARDS:-}" == "1" ]]; then CHAOS_NOTE="join-shards+${CHAOS_NOTE}"; fi

stamp_multidc() {
  local stamp="$1" outcome="$2" notes="$3" reg="${4:--}" app="${5:--}" latency="${6:-}"
  local git date_iso lat_line hist_line prior tmp
  git="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  date_iso="$(date -Iseconds 2>/dev/null || date)"
  lat_line=""
  if [[ -n "$latency" ]]; then lat_line="| latency | $latency |"$'\n'; fi
  hist_line="| \`$stamp\` | $MODE_LABEL | $reg | $app | $outcome | $notes |"
  prior=""
  if [[ -f "$RESULTS" ]]; then
    # Merge prior History rows (do not wipe D/E/F/G when I stamps).
    while IFS= read -r line || [[ -n "$line" ]]; do
      if [[ "$line" =~ ^\|[[:space:]]*\` ]]; then
        if [[ "$line" == *"$stamp"* ]]; then continue; fi
        if [[ "$line" == *"pending-full-run"* ]]; then continue; fi
        prior+="$line"$'\n'
      fi
    done < <(awk '/^## History/{f=1;next} f && /^\| `/{print}' "$RESULTS" 2>/dev/null || true)
  fi
  prior+="$hist_line"
  tmp="$(mktemp)"
  cat > "$tmp" <<EOF
# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent \`:valid? true\`).

## Latest stamp ($MODE_LABEL)

| Field | Value |
|-------|--------|
| stamp | \`$stamp\` |
| date | $date_iso |
| git | $git |
| host | $(if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then echo gha; else hostname 2>/dev/null || echo unknown; fi) |
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
$prior
EOF
  mv "$tmp" "$RESULTS"
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

if [[ -n "${STAMP:-}" ]]; then
  STAMP_BASE="$STAMP"
elif [[ "$MULTIDC_NEMESIS" == "0" ]]; then
  STAMP_BASE="multidc-$MODE-nochao-$(date +%Y-%m-%d-%H%M 2>/dev/null || echo run)"
else
  STAMP_BASE="multidc-$MODE-chaos-$(date +%Y-%m-%d-%H%M 2>/dev/null || echo run)"
fi
if [[ "${JEPSEN_UNCLEAN_REVIVE:-0}" == "1" ]]; then
  STAMP_BASE="${STAMP:-multidc-$MODE-unclean-$(date +%Y-%m-%d-%H%M 2>/dev/null || echo run)}"
fi
if [[ "${JEPSEN_JOIN_SHARDS:-}" == "1" && -z "${STAMP:-}" ]]; then
  STAMP_BASE="multidc-$MODE-join-$(date +%Y-%m-%d-%H%M 2>/dev/null || echo run)"
fi
export DOCKER_BUILDKIT=1
# Compose interpolates MULTIDC_MODE into config paths — export before validate/up.
export MULTIDC_MODE

if [[ "$SKIP_REBUILD" == "1" ]] && docker images -q jamoa-grid-jepsen:local | grep -q .; then
  echo "Skip rebuild: using existing jamoa-grid-jepsen:local"
else
  "$JEPSEN_DIR/scripts/build-jepsen-image.sh"
fi

# shellcheck source=../../scripts/jepsen-client-version.sh
source "$JEPSEN_DIR/scripts/jepsen-client-version.sh"
jepsen_sync_project_clj "$ROOT"
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
  if (( ok != 1 )); then
    echo "ERROR: Multi-DC nodes not healthy before settle" >&2
    return 1
  fi
  if [[ -x "$JEPSEN_DIR/scripts/wait-writer-eligible.sh" ]]; then
    echo "Waiting for Active writerEligible (a1-a3 readiness)..."
    # ASYNC cold-start connect storms: longer post-ready settle before generators.
    if [[ "$MODE" == "async" || "$MODE" == "async-swarm" ]]; then
      export POST_READY_SLEEP_SEC="${POST_READY_SLEEP_SEC:-20}"
      export WRITER_SETTLE_DEADLINE_SEC="${WRITER_SETTLE_DEADLINE_SEC:-240}"
    fi
    # Hard-fail: generators must not start without a phase-ranked writer.
    bash "$JEPSEN_DIR/scripts/wait-writer-eligible.sh" "7777,7778,7779"
  fi
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
    -e "JEPSEN_SWARM=${JEPSEN_SWARM:-}" \
    -e "JEPSEN_JOIN_SHARDS=${JEPSEN_JOIN_SHARDS:-}" \
    -e "JEPSEN_MULTIDC=1" \
    jepsen bash /jepsen/scripts/run-workload-multidc.sh "$workload" "$TIME_LIMIT" $nem_arg 2>&1 || true)"
  echo "$out"
  local tmp
  tmp="$(mktemp)"
  printf '%s\n' "$out" >"$tmp"
  local honesty_notes=""
  if [[ -x "$JEPSEN_DIR/scripts/jepsen-honesty-gate.sh" ]]; then
    honesty_notes="$("$JEPSEN_DIR/scripts/jepsen-honesty-gate.sh" "$tmp" 2>&1 || true)"
    echo "$honesty_notes"
    if echo "$honesty_notes" | grep -Fq 'HONESTY_FAIL='; then
      rm -f "$tmp"
      # Join (or any) profile: schema spam ⇒ FAIL even if Elle :valid? true.
      if [[ "${JEPSEN_JOIN_SHARDS:-}" == "1" ]] || [[ "$workload" == "join" ]]; then
        return 1
      fi
    fi
  fi
  rm -f "$tmp"
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

dump_cluster_logs_if_needed() {
  local ec="${1:-}"
  if [[ -z "$ec" ]]; then
    if [[ "${ALL_PASS:-1}" == "1" ]]; then
      return 0
    fi
    ec=1
  elif [[ "$ec" -eq 0 ]]; then
    return 0
  fi
  echo "=== FAIL (exit=$ec): dumping cluster docker logs before purge ==="
  if [[ -f "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" ]]; then
    local dump_dir="$JEPSEN_DIR/cluster-logs/${STAMP_BASE:-multidc}-${MODE:-run}"
    FAIL_CLASS="${FAIL_CLASS:-}" HARNESS_REASON="${HARNESS_REASON:-}" \
      bash "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" "$dump_dir" || true
  fi
}

ensure_cluster
release_jepsen_ports() {
  local _ec=$?
  dump_cluster_logs_if_needed "$_ec" || true
  echo "Releasing Jepsen host ports (1dc+multidc compose down)..."
  if [[ -x "$JEPSEN_DIR/scripts/jepsen-purge.sh" ]]; then
    bash "$JEPSEN_DIR/scripts/jepsen-purge.sh" all || true
  else
    docker compose down -v --remove-orphans || true
  fi
}
trap release_jepsen_ports EXIT

# Parse MULTIDC_WORKLOADS=register,append | append | join
IFS=',' read -r -a WORKLOAD_LIST <<< "$MULTIDC_WORKLOADS"
REG_OUTCOME="n/a"
APP_OUTCOME="n/a"
JOIN_OUTCOME="n/a"
ALL_PASS=1
FIRST=1
for wl in "${WORKLOAD_LIST[@]}"; do
  wl="$(echo "$wl" | tr -d '[:space:]')"
  [[ -n "$wl" ]] || continue
  if [[ "$FIRST" != "1" ]]; then
    ensure_cluster
  fi
  FIRST=0
  echo "=== Multi-DC workload: $wl, nemesis=$MULTIDC_NEMESIS swarm=${JEPSEN_SWARM:-0} join=${JEPSEN_JOIN_SHARDS:-0} ==="
  if run_workload "$wl"; then
    outcome=PASS
  else
    outcome=FAIL
    ALL_PASS=0
    dump_cluster_logs_if_needed 1 || true
  fi
  case "$wl" in
    register) REG_OUTCOME="$outcome" ;;
    append) APP_OUTCOME="$outcome" ;;
    join) JOIN_OUTCOME="$outcome"; APP_OUTCOME="$outcome" ;;
    *) APP_OUTCOME="$outcome" ;;
  esac
done

# Best-effort: scrape no-proposer diag from last workload docker logs / leftover out is not kept;
# clojure teardown prints jepsen-diag no-proposer-count=N into lein stdout (captured above per wl).
NOTES="FULL lein chaos=$CHAOS_NOTE time-limit=$TIME_LIMIT workloads=$MULTIDC_WORKLOADS; register=$REG_OUTCOME; append=$APP_OUTCOME; join=$JOIN_OUTCOME"
echo "=== Multi-DC outcomes register=$REG_OUTCOME append=$APP_OUTCOME join=$JOIN_OUTCOME ==="
if [[ "$ALL_PASS" == "1" ]]; then
  stamp_multidc "$STAMP_BASE" "PASS" "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
  exit 0
fi
stamp_multidc "$STAMP_BASE" "FAIL" "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
dump_cluster_logs_if_needed 1 || true
exit 1