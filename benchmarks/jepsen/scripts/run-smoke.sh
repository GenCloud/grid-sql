#!/usr/bin/env bash
# Jepsen harness smoke: compose config + optional chaos ITs + RESULTS stamp.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
cd "$ROOT"

COMPOSE_STATUS="validated"
CHAOS="skipped"
FULL="not-run"
OUTCOME="HARNESS_READY"
NOTES="compose config ok; full Clojure Jepsen not run"
FAILED=0

echo "=== jepsen smoke ==="
if ! docker compose config -q; then
  echo "FAIL: docker compose config"
  COMPOSE_STATUS="invalid"
  OUTCOME="FAIL"
  FAILED=1
else
  echo "OK: docker compose config"
fi

# Optional: chaos IT baseline when JDK 25+ available
if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "${JAVA_HOME}/bin/java" ]]; then
  ver="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 || true)"
  echo "JAVA_HOME=$JAVA_HOME ($ver)"
  if command -v mvn >/dev/null 2>&1; then
    set +e
    (cd "$REPO" && mvn -B -pl grid-server-core \
      -Dtest=index.unit.replication.chaos.LinearizabilityIT,index.unit.replication.chaos.Partition3NodeIT \
      test)
    mvn_rc=$?
    set -e
    if [[ $mvn_rc -eq 0 ]]; then
      CHAOS="pass"
      NOTES="compose config ok; chaos ITs pass; full Jepsen not run (no lein or skipped)"
    else
      CHAOS="fail"
      OUTCOME="FAIL"
      NOTES="chaos ITs failed"
      FAILED=1
    fi
  fi
else
  echo "Skip chaos ITs (set JAVA_HOME to JDK 25 to enable)"
fi

if command -v lein >/dev/null 2>&1; then
  NOTES="${NOTES}; lein present — run scripts/run-jepsen.sh for full"
else
  NOTES="${NOTES}; lein absent on this host"
fi

export STAMP="${STAMP:-$(date +%Y-%m-%d)-jepsen-smoke}"
export MODE=smoke OUTCOME COMPOSE_STATUS CHAOS FULL NOTES
export COMMAND="run-jepsen-smoke.sh"
bash "$ROOT/scripts/stamp-results.sh"

exit "$FAILED"
