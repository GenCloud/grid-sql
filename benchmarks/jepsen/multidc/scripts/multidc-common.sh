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
# shellcheck source=../../scripts/jepsen-instance-env.sh
. "$JEPSEN_DIR/scripts/jepsen-instance-env.sh"
RESULTS="${JEPSEN_RESULTS_FILE:-$MULTIDC_DIR/RESULTS.md}"
cd "$MULTIDC_DIR"

export MULTIDC_MODE="$MODE"
export JEPSEN_M2="${JEPSEN_M2:-${HOME}/.m2}"
export JEPSEN_NODES="${JEPSEN_NODES:-a1,a2,a3,b1,b2}"
export JEPSEN_HTTP_PORTS="${JEPSEN_HTTP_PORTS:-$JEPSEN_HTTP_PORTS_MDC}"
export JEPSEN_SQL_PORTS="${JEPSEN_SQL_PORTS:-$JEPSEN_SQL_PORTS_MDC}"
export JEPSEN_USE_LOCALHOST="${JEPSEN_USE_LOCALHOST:-0}"
export JEPSEN_MULTI_HOST="${JEPSEN_MULTI_HOST:-1}"
MDC_PREFIX="${JEPSEN_MDC_CTR_PREFIX:-jamoa-multidc}"
CONTROL_NAME="${CONTROL_NAME:-${MDC_PREFIX}-control}"
HOST_SQL_URL="grid://grid:grid@127.0.0.1:${JEPSEN_HOST_SQL_N1},127.0.0.1:${JEPSEN_HOST_SQL_N2},127.0.0.1:${JEPSEN_HOST_SQL_N3},127.0.0.1:${JEPSEN_HOST_SQL_B1}/public"
ACTIVE_HTTP_PORTS="${JEPSEN_HOST_HTTP_N1},${JEPSEN_HOST_HTTP_N2},${JEPSEN_HOST_HTTP_N3}"
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
# CHAOS_NOTE must match the Clojure generator (core.clj nemesis-schedule), not a label dump.
# Evidence: unclean RESULTS claimed dc-link+kill-dc-a while JEPSEN_UNCLEAN_REVIVE=1 only fires
# sleep12 → :kill-proposer → sleep28 (nemesis-unclean-revive.sh).
if [[ "${JEPSEN_UNCLEAN_REVIVE:-0}" == "1" ]]; then
  CHAOS_NOTE="unclean-revive"
elif [[ "$MULTIDC_NEMESIS" == "1" ]]; then
  if [[ "${JEPSEN_SWARM:-}" == "1" ]]; then
    CHAOS_NOTE="swarm-bounce+dc-link+kill-voter"
  else
    CHAOS_NOTE="dc-link+kill-voter+kill-dc-a+revive-dc-a"
  fi
else
  CHAOS_NOTE="no-nemesis"
fi
if [[ "${JEPSEN_JOIN_SHARDS:-}" == "1" ]]; then CHAOS_NOTE="join-shards+${CHAOS_NOTE}"; fi
# Fail class for SUMMARY / stamp notes: harness (compose) vs elle (lein Analysis).
FAIL_CLASS=""
HARNESS_REASON=""
COMPOSE_UP_MAX_ATTEMPTS="${COMPOSE_UP_MAX_ATTEMPTS:-3}"
COMPOSE_UP_SETTLE_SEC="${COMPOSE_UP_SETTLE_SEC:-8}"

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
| host | $(hostname 2>/dev/null || echo unknown) |
| mode | $MODE_LABEL ($MODE) |
| outcome | \`$outcome\` |
| register | $reg |
| append | $app |
| chaos | $CHAOS_NOTE |
${lat_line}| notes | $notes |

Multi-host SQL URL:

\`\`\`
${HOST_SQL_URL}
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

echo "=== Multi-DC $MODE_LABEL (nemesis=$MULTIDC_NEMESIS instance=${JEPSEN_INSTANCE:-default} offset=${JEPSEN_PORT_OFFSET:-0}) ==="
echo "SQL URL: ${HOST_SQL_URL}"

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

if [[ "${JEPSEN_SKIP_MVN_INSTALL:-0}" == "1" ]]; then
  echo "Skip per-cell mvn install (JEPSEN_SKIP_MVN_INSTALL=1; use shared ~/.m2)"
else
  echo "Installing grid-sql-client..."
  ( cd "$ROOT" && run_mvn -B -pl grid-sql-client -am install -Dmaven.test.skip=true )
fi

docker_daemon_ready() {
  if docker info >/dev/null 2>&1; then
    echo "ensure_cluster: docker info ok"
    return 0
  fi
  echo "ensure_cluster: docker info FAILED" >&2
  return 1
}

# Evidence 2026-10-05 matrix/r2: Windows Docker Desktop returns "i/o timeout" on compose up
# after purge; bash set -e exited with no stamp → stale PASS in RESULTS.md.
# Bounded retry (not infinite); classify as HARNESS_FAIL when exhausted.
docker_compose_up_retry() {
  local attempt=1
  local up_out=""
  local up_ec=0
  while (( attempt <= COMPOSE_UP_MAX_ATTEMPTS )); do
    if ! docker_daemon_ready; then
      echo "ensure_cluster: compose up attempt=$attempt settle ${COMPOSE_UP_SETTLE_SEC}s (daemon not ready)"
      sleep "$COMPOSE_UP_SETTLE_SEC"
    fi
    echo "ensure_cluster: compose up attempt=$attempt/$COMPOSE_UP_MAX_ATTEMPTS"
    set +e
    up_out="$(docker compose up -d --force-recreate a1 a2 a3 b1 b2 2>&1)"
    up_ec=$?
    set -e
    echo "$up_out"
    if [[ "$up_ec" -eq 0 ]]; then
      # Retry recovered — do not carry HARNESS_REASON into a later Elle FAIL stamp.
      HARNESS_REASON=""
      FAIL_CLASS=""
      echo "ensure_cluster: compose up attempt=$attempt exit=0"
      return 0
    fi
    echo "ensure_cluster: compose up attempt=$attempt exit=$up_ec" >&2
    if echo "$up_out" | grep -Eiq 'i/o timeout|Error response from daemon|Cannot connect to the Docker daemon'; then
      HARNESS_REASON="docker-io-timeout"
      FAIL_CLASS="harness"
      echo "ensure_cluster: CLASS=harness reason=$HARNESS_REASON"
      if (( attempt < COMPOSE_UP_MAX_ATTEMPTS )); then
        echo "ensure_cluster: settle ${COMPOSE_UP_SETTLE_SEC}s before retry"
        sleep "$COMPOSE_UP_SETTLE_SEC"
      fi
    else
      HARNESS_REASON="compose-up-exit-$up_ec"
      FAIL_CLASS="harness"
      break
    fi
    attempt=$((attempt + 1))
  done
  return 1
}

ensure_cluster() {
  echo "ensure_cluster: begin prefix=$MDC_PREFIX unclean=${JEPSEN_UNCLEAN_REVIVE:-0}"
  # Parallel cells: only tear down this COMPOSE_PROJECT_NAME. Singleton: full purge frees binds.
  if [[ -n "${JEPSEN_INSTANCE:-}" ]]; then
    docker compose down -v --remove-orphans || true
  elif [[ -x "$JEPSEN_DIR/scripts/jepsen-purge.sh" ]]; then
    bash "$JEPSEN_DIR/scripts/jepsen-purge.sh" all || true
  else
    docker compose down -v --remove-orphans || true
  fi
  if ! docker_compose_up_retry; then
    echo "ERROR: Multi-DC compose up failed after retries (CLASS=${FAIL_CLASS:-harness} reason=${HARNESS_REASON:-compose-up})" >&2
    return 1
  fi
  echo "Waiting for health (prefix=$MDC_PREFIX)..."
  deadline=$((SECONDS + 240))
  ok=0
  while (( SECONDS < deadline )); do
    ok=1
    for name in "${MDC_PREFIX}-a1" "${MDC_PREFIX}-a2" "${MDC_PREFIX}-a3" "${MDC_PREFIX}-b1" "${MDC_PREFIX}-b2"; do
      st=$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null || echo missing)
      [[ "$st" == "healthy" ]] || ok=0
    done
    (( ok == 1 )) && break
    sleep 8
  done
  if (( ok != 1 )); then
    echo "ERROR: Multi-DC nodes not healthy before settle" >&2
    FAIL_CLASS="harness"
    HARNESS_REASON="${HARNESS_REASON:-nodes-not-healthy}"
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
    if ! bash "$JEPSEN_DIR/scripts/wait-writer-eligible.sh" "$ACTIVE_HTTP_PORTS"; then
      FAIL_CLASS="harness"
      HARNESS_REASON="writer-eligible-timeout"
      return 1
    fi
  fi
  # Health/writer path succeeded — clear any transient compose-retry harness flags.
  HARNESS_REASON=""
  FAIL_CLASS=""
  echo "ensure_cluster: end ok"
  return 0
}

# Stamp FAIL with CLASS=harness when compose dies before lein (no stale PASS).
stamp_harness_fail() {
  local phase="${1:-ensure_cluster}"
  FAIL_CLASS="harness"
  HARNESS_REASON="${HARNESS_REASON:-docker-io-timeout}"
  local notes="HARNESS_FAIL=${HARNESS_REASON} CLASS=harness phase=${phase}; chaos=$CHAOS_NOTE time-limit=$TIME_LIMIT workloads=$MULTIDC_WORKLOADS; register=${REG_OUTCOME:-n/a}; append=${APP_OUTCOME:-n/a}; join=${JOIN_OUTCOME:-n/a}"
  echo "CLASS=harness HARNESS_FAIL=${HARNESS_REASON} phase=${phase}"
  stamp_multidc "$STAMP_BASE" "FAIL" "$notes" "${REG_OUTCOME:-n/a}" "${APP_OUTCOME:-n/a}"
  dump_cluster_logs_if_needed 1 || true
}

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
    FAIL_CLASS="elle"
    HARNESS_REASON=""
    echo "CLASS=elle reason=Analysis-invalid workload=$workload"
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
    FAIL_CLASS="elle"
    HARNESS_REASON=""
    echo "CLASS=elle reason=valid-false workload=$workload"
    return 1
  fi
  if echo "$out" | grep -Eq 'jepsen\.core \{.*:valid\? true\}'; then
    return 0
  fi
  if [[ -z "$code" ]]; then
    FAIL_CLASS="harness"
    HARNESS_REASON="no-lein-exit"
    echo "CLASS=harness reason=no-lein-exit workload=$workload"
  fi
  return "${code:-1}"
}

# First ensure_cluster runs after dump helpers are defined (see below).
# Copy control-container Jepsen store onto the host so GHA upload-artifact globs
# (benchmarks/jepsen/clojure/store/**/history.edn) actually receive Elle histories.
# Evidence: GHA 37235523879 multidc artifacts were ~6KB RESULTS-only — store never left Docker.
# Must run while control is still up (after each workload), not only in EXIT trap after purge.
# Convert Git-Bash /d/foo path to a docker.exe-friendly Windows path (D:\foo).
# MSYS_NO_PATHCONV=1 leaves POSIX paths; docker cp then fails silently/best-effort.
jepsen_host_path_for_docker() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$p"
    return 0
  fi
  if [[ "$p" =~ ^/([a-zA-Z])/(.*)$ ]]; then
    local drive="${BASH_REMATCH[1]}"
    local rest="${BASH_REMATCH[2]}"
    # uppercase drive letter without relying on ^^ (bash 4+)
    drive="$(printf '%s' "$drive" | tr 'a-z' 'A-Z')"
    printf '%s\n' "${drive}:\\${rest//\//\\}"
    return 0
  fi
  printf '%s\n' "$p"
}

export_jepsen_store_to_host() {
  local host_store="$JEPSEN_DIR/clojure/store"
  local host_tar="$JEPSEN_DIR/clojure/jepsen-store-export.tar"
  local container_tar="/tmp/jepsen-store-export.tar"
  local host_tar_win
  mkdir -p "$host_store"
  if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -Fxq "$CONTROL_NAME"; then
    echo "export_jepsen_store_to_host: control $CONTROL_NAME not running; skip"
    return 0
  fi
  # Evidence (calm G-r2 2026-10-05 + GHA 37235523879):
  # - store lives only in the control container; EXIT purge wiped it before GHA upload
  # - docker cp of the store *tree* fails on Windows when Jepsen 'current'/'latest'
  #   symlinks are present ("A required privilege is not held by the client")
  # Primary: stream tar over exec (no host path for docker cp; pipefail is on).
  echo "export_jepsen_store_to_host: tar stream from $CONTROL_NAME (exclude current/latest)"
  if docker exec "$CONTROL_NAME" bash -lc \
      'tar cf - --exclude=current --exclude=latest -C /jepsen/jamoa/store .' \
      | tar -C "$host_store" -xf -; then
    find "$host_store" -type f -name 'history.edn' 2>/dev/null | wc -l \
      | awk '{print "export_jepsen_store_to_host: history.edn count="$1}'
    return 0
  fi
  echo "export_jepsen_store_to_host: stream failed; fallback single-file docker cp"
  if ! docker exec "$CONTROL_NAME" bash -lc \
      "rm -f '$container_tar' && tar cf '$container_tar' --exclude=current --exclude=latest -C /jepsen/jamoa/store ."; then
    echo "export_jepsen_store_to_host: in-container tar failed (best-effort)"
    return 0
  fi
  host_tar_win="$(jepsen_host_path_for_docker "$host_tar")"
  if ! docker cp "$CONTROL_NAME:$container_tar" "$host_tar_win"; then
    echo "export_jepsen_store_to_host: docker cp tar failed dest=$host_tar_win (best-effort)"
    return 0
  fi
  if ! tar -C "$host_store" -xf "$host_tar"; then
    echo "export_jepsen_store_to_host: host untar failed (best-effort)"
    return 0
  fi
  rm -f "$host_tar" || true
  docker exec "$CONTROL_NAME" rm -f "$container_tar" >/dev/null 2>&1 || true
  find "$host_store" -type f -name 'history.edn' 2>/dev/null | wc -l \
    | awk '{print "export_jepsen_store_to_host: history.edn count="$1}'
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
  echo "=== FAIL (exit=$ec): export store + dump cluster docker logs before purge ==="
  echo "CLASS=${FAIL_CLASS:-unknown} HARNESS_REASON=${HARNESS_REASON:-}"
  export_jepsen_store_to_host || true
  if [[ -x "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" ]]; then
    # Stable path under upload glob benchmarks/jepsen/cluster-logs/**
    # Dump BEFORE purge so Created/partial containers still appear in docker-ps.
    local dump_dir="$JEPSEN_DIR/cluster-logs/${STAMP_BASE:-multidc}-${MODE:-run}"
    FAIL_CLASS="${FAIL_CLASS:-}" HARNESS_REASON="${HARNESS_REASON:-}" \
      bash "$JEPSEN_DIR/scripts/dump-jepsen-cluster-logs.sh" "$dump_dir" || true
  fi
}

release_jepsen_ports() {
  local _ec=$?
  # Always export store before purge (PASS and FAIL) so GHA artifacts carry histories.
  export_jepsen_store_to_host || true
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

# First compose up (after helpers defined) — stamp HARNESS_FAIL on docker i/o timeout.
if ! ensure_cluster; then
  stamp_harness_fail "ensure_cluster-first"
  exit 1
fi

for wl in "${WORKLOAD_LIST[@]}"; do
  wl="$(echo "$wl" | tr -d '[:space:]')"
  [[ -n "$wl" ]] || continue
  if [[ "$FIRST" != "1" ]]; then
    # Evidence unclean-r2: second ensure_cluster after register hit docker i/o timeout.
    if ! ensure_cluster; then
      ALL_PASS=0
      case "$wl" in
        append|join) APP_OUTCOME="FAIL" ;;
        register) REG_OUTCOME="FAIL" ;;
      esac
      stamp_harness_fail "ensure_cluster-before-$wl"
      exit 1
    fi
  fi
  FIRST=0
  echo "=== Multi-DC workload: $wl, nemesis=$MULTIDC_NEMESIS swarm=${JEPSEN_SWARM:-0} join=${JEPSEN_JOIN_SHARDS:-0} unclean=${JEPSEN_UNCLEAN_REVIVE:-0} ==="
  if run_workload "$wl"; then
    outcome=PASS
  else
    outcome=FAIL
    ALL_PASS=0
    # Dump before next ensure_cluster / EXIT purge wipes containers.
    dump_cluster_logs_if_needed 1 || true
  fi
  # Pull histories while control is still alive (before next ensure_cluster restarts it).
  export_jepsen_store_to_host || true
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
if [[ "${FAIL_CLASS:-}" == "harness" ]]; then
  NOTES="CLASS=harness HARNESS_FAIL=${HARNESS_REASON:-unknown}; $NOTES"
elif [[ -n "${FAIL_CLASS:-}" ]]; then
  NOTES="CLASS=${FAIL_CLASS}; $NOTES"
fi
echo "=== Multi-DC outcomes register=$REG_OUTCOME append=$APP_OUTCOME join=$JOIN_OUTCOME CLASS=${FAIL_CLASS:-ok} ==="
if [[ "$ALL_PASS" == "1" ]]; then
  stamp_multidc "$STAMP_BASE" "PASS" "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
  exit 0
fi
stamp_multidc "$STAMP_BASE" "FAIL" "$NOTES" "$REG_OUTCOME" "$APP_OUTCOME"
dump_cluster_logs_if_needed 1 || true
exit 1