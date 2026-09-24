param([string]$Stamp = "")
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Peers = Join-Path $Root "benchmarks\oss-peers"
$Results = Join-Path $Root "grid-server-core\benchmarks\lab"
New-Item -ItemType Directory -Force -Path $Results | Out-Null
if (-not $Stamp) { $Stamp = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { Get-Date -Format "yyyy-MM-dd" } }
$env:JMH_STAMP = $Stamp
$env:COMPARE_RESULTS_DIR = $Results
$env:COMPARE_OPS = if ($env:COMPARE_OPS) { $env:COMPARE_OPS } else { "200" }
Write-Host "=== run-compare-ignite (thin+embed) stamp=$Stamp ==="
Push-Location $Peers
try {
  docker compose up -d ignite | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "docker compose up ignite failed" }
} finally { Pop-Location }
$deadline = (Get-Date).AddMinutes(3)
do {
  Start-Sleep -Seconds 5
  try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect("127.0.0.1", 10800)
    $tcp.Close()
    break
  } catch {}
} while ((Get-Date) -lt $deadline)
Set-Location $Root
# Living peer: thin client to compose :10800
mvn -q -pl grid-server-core -Pjmh "-Dtest=IgniteThinCompareHarness" "-Dsurefire.failIfNoSpecifiedTests=false" test
# Fair IMDG-class peer: embedded IgniteCache (same JVM)
mvn -q -pl grid-server-core -Pjmh "-Dtest=IgniteEmbeddedCompareHarness" "-Dsurefire.failIfNoSpecifiedTests=false" test
Write-Host "Ignite compare done stamp=$Stamp (thin+embed)"
