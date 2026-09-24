#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export DOCKER_BUILDKIT=1
TAG="${1:-jamoa-grid-sql:local}"
mvn -B -pl grid-sql-server-starter -am package -Dmaven.test.skip=true
JAR="$ROOT/grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar"
mkdir -p "$ROOT/examples/docker/staging"
cp -f "$JAR" "$ROOT/examples/docker/staging/app.jar"
docker build -f examples/docker/Dockerfile --build-arg APP_JAR=examples/docker/staging/app.jar -t "$TAG" .
echo "OK image $TAG"
