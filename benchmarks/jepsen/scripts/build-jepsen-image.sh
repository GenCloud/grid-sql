#!/usr/bin/env bash
# Host-jar profile: mvn on host, slim Docker image (no Maven Central inside Docker).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
IMAGE="${1:-jamoa-grid-jepsen:local}"
export DOCKER_BUILDKIT=1

echo "mvn package example-app (host)..."
(cd "$ROOT" && mvn -B -pl example-app -am package -Dmaven.test.skip=true)

JAR="$(ls -1t "$ROOT"/example-app/target/example-app-*.jar 2>/dev/null | grep -vE 'sources|javadoc|original' | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "example-app jar not found" >&2
  exit 1
fi
REL="example-app/target/$(basename "$JAR")"
echo "docker build runtime-hostjar APP_JAR=$REL ..."
docker build -f "$ROOT/benchmarks/jepsen/Dockerfile" --target runtime-hostjar \
  --build-arg "APP_JAR=$REL" -t "$IMAGE" "$ROOT"
echo "OK: $IMAGE"
