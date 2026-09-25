#!/usr/bin/env bash
# Host-jar profile: mvn on host, slim Docker image (no Maven Central inside Docker).
# Stages fat jar into benchmarks/jepsen/docker-staging/ so **/target stays dockerignored.
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
STAGING_DIR="$JEPSEN_DIR/docker-staging"
IMAGE="${1:-jamoa-grid-jepsen:local}"
export DOCKER_BUILDKIT=1

echo "mvn package grid-sql-jepsen-starter (host)..."
(cd "$ROOT" && run_mvn -B -pl grid-sql-jepsen-starter -am package -Dmaven.test.skip=true)

JAR="$(ls -1t "$ROOT"/grid-sql-jepsen-starter/target/grid-sql-jepsen-starter-*.jar 2>/dev/null | grep -vE 'sources|javadoc|original' | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "grid-sql-jepsen-starter jar not found" >&2
  exit 1
fi

mkdir -p "$STAGING_DIR"
cp -f "$JAR" "$STAGING_DIR/app.jar"
echo "Staged $(basename "$JAR") -> benchmarks/jepsen/docker-staging/app.jar"

echo "docker build runtime-hostjar ..."
docker build -f "$ROOT/benchmarks/jepsen/Dockerfile" --target runtime-hostjar \
  --build-arg "APP_JAR=benchmarks/jepsen/docker-staging/app.jar" -t "$IMAGE" "$ROOT"
echo "OK: $IMAGE"