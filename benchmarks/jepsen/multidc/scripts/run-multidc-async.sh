#!/usr/bin/env bash
# Multi-DC Jepsen — ASYNC_SHIP (scaffold or MULTIDC_FULL=1 → Docker+lein).
set -euo pipefail
MULTIDC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JEPSEN_DIR="$(cd "$MULTIDC_DIR/.." && pwd)"
cd "$MULTIDC_DIR"

export MULTIDC_MODE=async
export JEPSEN_M2="${JEPSEN_M2:-${HOME}/.m2}"
TIME_LIMIT="${JEPSEN_TIME_LIMIT:-60}"

echo "=== Multi-DC ASYNC_SHIP ==="
echo "SQL URL: grid://@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public"
echo "Validating docker compose config..."
docker compose config >/dev/null
echo "compose config OK (MULTIDC_MODE=$MULTIDC_MODE)"

if [[ "${MULTIDC_FULL:-0}" != "1" ]]; then
  echo "Scaffold only. Set MULTIDC_FULL=1 for Docker+lein (prefer PowerShell run-multidc-async.ps1 -Full on Windows)."
  exit 0
fi

if command -v pwsh >/dev/null 2>&1; then
  exec pwsh -NoProfile -File "$MULTIDC_DIR/scripts/run-multidc-full.ps1" -Mode async -Full -TimeLimit "$TIME_LIMIT"
fi

echo "FULL bash path: build image + start 5-node cluster + lein via control..."
export DOCKER_BUILDKIT=1
"$JEPSEN_DIR/scripts/build-jepsen-image.sh"
docker compose down -v --remove-orphans || true
docker compose up -d --force-recreate a1 a2 a3 b1 b2

deadline=$((SECONDS + 300))
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

docker compose --profile control up -d jepsen
deadline=$((SECONDS + 600))
while (( SECONDS < deadline )); do
  if docker compose --profile control exec -T jepsen bash -lc 'test -f /tmp/jepsen-control-ready && command -v lein' >/dev/null 2>&1; then
    break
  fi
  sleep 5
done

run_wl() {
  local wl="$1"
  docker compose --profile control exec -T jepsen bash -lc "
set +e
export PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export JAVA_HOME=/opt/java/openjdk JAVA_CMD=/opt/java/openjdk/bin/java
export JVM_OPTS='-Xmx2g -XX:+UseG1GC' LEIN_JVM_OPTS='-Xmx1g' JAVA_TOOL_OPTIONS='--enable-preview -Xmx2g'
cd /jepsen/jamoa
export JEPSEN_NODES=a1,a2,a3,b1,b2
export JEPSEN_HTTP_PORTS=7777,7778,7779,7780,7781
export JEPSEN_SQL_PORTS=15432,15433,15434,15435,15436
export JEPSEN_SCRIPTS=/jepsen/scripts JEPSEN_USE_LOCALHOST=0 JEPSEN_MULTIDC=1 JEPSEN_MULTI_HOST=1
export JEPSEN_GRID_URL='grid://@a1:15432,a2:15433,a3:15434,b1:15435/public?maxConnections=1&maxTxContexts=64'
lein run -m jamoa-jepsen.core test --workload $wl --time-limit $TIME_LIMIT
echo LEIN_EXIT=\$?
"
}

echo "=== register ==="
run_wl register
docker compose down -v --remove-orphans || true
docker compose up -d --force-recreate a1 a2 a3 b1 b2
sleep 30
echo "=== append ==="
run_wl append
echo "Stamp multidc/RESULTS.md on host after inspecting store/ (use latency-from-history.ps1)."
exit 0