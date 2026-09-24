param(
  [switch]$TryCompose
)
$ErrorActionPreference = "Continue"
$WitnessDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$MultidcDir = (Resolve-Path (Join-Path $WitnessDir "../multidc")).Path
$Root = (Resolve-Path (Join-Path $WitnessDir "../../..")).Path

Write-Host "Witness Jepsen precondition check (TD-HA-001)"
Write-Host ("repo: " + $Root)

$dockerOk = $false
try {
  docker info 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { $dockerOk = $true }
} catch { $dockerOk = $false }
Write-Host ("docker: " + $(if ($dockerOk) { "OK" } else { "MISSING/DOWN" }))

$imageOk = $false
if ($dockerOk) {
  $images = docker images jamoa-grid-jepsen:local --format "{{.Repository}}:{{.Tag}}" 2>$null
  if ($images -match "jamoa-grid-jepsen:local") { $imageOk = $true }
}
Write-Host ("image jamoa-grid-jepsen:local: " + $(if ($imageOk) { "OK" } else { "MISSING" }))

$leinOk = $false
try {
  $leinPath = Get-Command lein -ErrorAction SilentlyContinue
  if ($leinPath) { $leinOk = $true }
} catch { $leinOk = $false }
Write-Host ("lein (host): " + $(if ($leinOk) { "OK" } else { "MISSING (control container may still have it)" }))

Write-Host "stubs: configs/application-w1.yml + docker-compose.witness-overlay.yml"
Write-Host "RESULTS: Open until Docker+lein Witness chaos PASS"

if (-not $TryCompose) {
  Write-Host "Pass -TryCompose to attempt overlay up (requires image)."
  exit 0
}

if (-not $dockerOk -or -not $imageOk) {
  Write-Host "SKIP compose: docker or image missing - leaving RESULTS Open."
  exit 2
}

Set-Location $MultidcDir
$env:MULTIDC_MODE = "async"
$overlay = Join-Path $WitnessDir "docker-compose.witness-overlay.yml"
Write-Host ("docker compose -f docker-compose.yml -f " + $overlay + " config")
docker compose -f docker-compose.yml -f $overlay config 2>&1 | Select-Object -First 40
Write-Host "Compose config validated (or errors above). Full Witness PASS still requires lein chaos + RESULTS update."
exit 0