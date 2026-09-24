# Build product image jamoa-grid-sql:local from grid-sql-server-starter fat jar.
param(
  [string]$Tag = "jamoa-grid-sql:local"
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Set-Location $Root
$env:DOCKER_BUILDKIT = "1"

Write-Host "=== mvn package grid-sql-server-starter ==="
mvn -B -pl grid-sql-server-starter -am package "-Dmaven.test.skip=true"
if ($LASTEXITCODE -ne 0) { throw "mvn failed: $LASTEXITCODE" }

$jarDir = Join-Path $Root "grid-sql-server-starter\target"
$jar = Get-ChildItem -Path $jarDir -Filter "grid-sql-server-starter-*.jar" -File -ErrorAction SilentlyContinue |
	Where-Object { $_.Name -notmatch "(sources|javadoc|original)" } |
	Sort-Object LastWriteTime -Descending |
	Select-Object -First 1
if (-not $jar) { throw "Missing fat jar under $jarDir (grid-sql-server-starter-*.jar)" }
$jar = $jar.FullName
Write-Host "Using jar: $jar"

$staging = Join-Path $Root "examples\docker\staging"
New-Item -ItemType Directory -Force -Path $staging | Out-Null
Copy-Item -Force $jar (Join-Path $staging "app.jar")

Write-Host "=== docker build $Tag ==="
docker build -f examples/docker/Dockerfile --build-arg APP_JAR=examples/docker/staging/app.jar -t $Tag .
if ($LASTEXITCODE -ne 0) { throw "docker build failed: $LASTEXITCODE" }
Write-Host "OK image $Tag"
