# Host-jar profile: mvn on host, slim Docker image (no Maven Central inside Docker).
# Copies the fat jar into benchmarks/jepsen/docker-staging/ so **/target stays dockerignored.
param(
  [string]$Image = "jamoa-grid-jepsen:local"
)
$ErrorActionPreference = "Stop"
$JEPSEN_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ROOT = (Resolve-Path (Join-Path $JEPSEN_DIR "../..")).Path
$StagingDir = Join-Path $JEPSEN_DIR "docker-staging"
$env:DOCKER_BUILDKIT = "1"

Write-Host "mvn package grid-sql-jepsen-starter..."
Push-Location $ROOT
try {
  mvn -B -pl grid-sql-jepsen-starter -am package "-Dmaven.test.skip=true"
  if ($LASTEXITCODE -ne 0) { throw "mvn package failed: $LASTEXITCODE" }
} finally {
  Pop-Location
}

$target = Join-Path $ROOT "grid-sql-jepsen-starter\target"
$jar = Get-ChildItem -Path $target -Filter "grid-sql-jepsen-starter-*.jar" |
  Where-Object { $_.Name -notmatch "sources|javadoc|original" } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if (-not $jar) { throw "starter jar not found under grid-sql-jepsen-starter/target" }

New-Item -ItemType Directory -Force -Path $StagingDir | Out-Null
$staged = Join-Path $StagingDir "app.jar"
Copy-Item -Force $jar.FullName $staged
Write-Host "Staged $($jar.Name) -> benchmarks/jepsen/docker-staging/app.jar"

Write-Host "docker build runtime-hostjar ..."
Push-Location $ROOT
try {
  docker build -f benchmarks/jepsen/Dockerfile --target runtime-hostjar `
    --build-arg "APP_JAR=benchmarks/jepsen/docker-staging/app.jar" -t $Image .
  if ($LASTEXITCODE -ne 0) { throw "docker build failed: $LASTEXITCODE" }
} finally {
  Pop-Location
}
Write-Host "OK: $Image"
