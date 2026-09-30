<#
.SYNOPSIS
  Wipe Jepsen Compose volumes / containers (and optionally Jepsen images only).
.DESCRIPTION
  Corrupt op log length=0 is a dirty named volume problem, not a stale image problem.
  Never runs docker image prune / docker system prune — only jamoa-grid-jepsen* and
  volumes/containers from benchmarks/jepsen and benchmarks/jepsen/multidc compose projects.
  Always frees host SQL/HTTP binds (15432+ / 7777+) for the selected scope so the next
  matrix cell (1-DC → Multi-DC) cannot hit "port is already allocated".
#>
param(
  [ValidateSet("1dc", "multidc", "all")]
  [string]$Scope = "all",
  [switch]$PurgeImages,
  [switch]$PurgeM2
)

$ErrorActionPreference = "Continue"
$JEPSEN_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$MULTIDC_DIR = Join-Path $JEPSEN_DIR "multidc"
$WITNESS_OVERLAY = Join-Path $JEPSEN_DIR "witness\docker-compose.witness-overlay.yml"

function Invoke-ComposeDown([string]$Dir) {
  if (-not (Test-Path (Join-Path $Dir "docker-compose.yml"))) {
    Write-Host "WARN: no compose in $Dir"
    return
  }
  Push-Location $Dir
  try {
    Write-Host "compose down -v --remove-orphans in $Dir"
    docker compose down -v --remove-orphans 2>$null
  } finally {
    Pop-Location
  }
}

function Invoke-WitnessOverlayDown {
  if (-not (Test-Path $WITNESS_OVERLAY)) {
    return
  }
  if (-not (Test-Path (Join-Path $MULTIDC_DIR "docker-compose.yml"))) {
    return
  }
  Push-Location $MULTIDC_DIR
  try {
    Write-Host "compose down (witness overlay) in $MULTIDC_DIR"
    docker compose -f docker-compose.yml -f $WITNESS_OVERLAY down -v --remove-orphans 2>$null
  } finally {
    Pop-Location
  }
}

function Remove-NamedVolumes([string[]]$Names) {
  foreach ($v in $Names) {
    docker volume rm -f $v 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
      Write-Host "removed volume $v"
    }
  }
}

function Remove-LeftoverContainers([string[]]$Names) {
  foreach ($n in $Names) {
    $id = docker ps -aq --filter "name=^/${n}$" 2>$null
    if (-not $id) {
      $id = docker ps -aq --filter "name=$n" 2>$null
    }
    if ($id) {
      Write-Host "force-remove leftover container $n"
      docker rm -f $n 2>$null | Out-Null
    }
  }
}

Write-Host "=== jepsen-purge scope=$Scope purgeImages=$PurgeImages purgeM2=$PurgeM2 ==="
Write-Host "NOTE: OpLog Corrupt length=0 => wipe Jepsen DATA volumes only; never global docker prune."

if ($Scope -eq "1dc" -or $Scope -eq "all") {
  Invoke-ComposeDown $JEPSEN_DIR
  Remove-LeftoverContainers @(
    "jamoa-jepsen-n1", "jamoa-jepsen-n2", "jamoa-jepsen-n3", "jamoa-jepsen-jepsen"
  )
  Remove-NamedVolumes @(
    "jepsen_n1-data", "jepsen_n2-data", "jepsen_n3-data",
    "benchmarks_jepsen_n1-data", "benchmarks_jepsen_n2-data", "benchmarks_jepsen_n3-data"
  )
}

if ($Scope -eq "multidc" -or $Scope -eq "all") {
  Invoke-WitnessOverlayDown
  Invoke-ComposeDown $MULTIDC_DIR
  Remove-LeftoverContainers @(
    "jamoa-multidc-a1", "jamoa-multidc-a2", "jamoa-multidc-a3",
    "jamoa-multidc-b1", "jamoa-multidc-b2", "jamoa-multidc-w1",
    "jamoa-multidc-control"
  )
  Remove-NamedVolumes @(
    "multidc_a1-data", "multidc_a2-data", "multidc_a3-data",
    "multidc_b1-data", "multidc_b2-data", "multidc_w1-data", "w1-data"
  )
  if ($PurgeM2) {
    Remove-NamedVolumes @("multidc_multidc-jepsen-m2", "multidc_multidc-jepsen-work")
  }
}

if ($PurgeImages) {
  Write-Host "Removing only jamoa-grid-jepsen images except :local (no docker image prune)..."
  $ids = docker images "jamoa-grid-jepsen" --format "{{.ID}} {{.Repository}}:{{.Tag}}" 2>$null
  if ($ids) {
    foreach ($line in ($ids -split "`n")) {
      $t = $line.Trim()
      if (-not $t) { continue }
      if ($t -match "jamoa-grid-jepsen:local\s*$" -or $t -match "jamoa-grid-jepsen:local$") {
        Write-Host "keep $t"
        continue
      }
      $id = ($t -split "\s+")[0]
      Write-Host "removing Jepsen image $t"
      docker rmi -f $id 2>$null | Out-Null
    }
  }
}

Write-Host "jepsen-purge done"
exit 0
