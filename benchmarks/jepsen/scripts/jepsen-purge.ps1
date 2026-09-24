<#
.SYNOPSIS
  Wipe Jepsen Compose volumes / containers (and optionally Jepsen images only).
.DESCRIPTION
  Corrupt op log length=0 is a dirty named volume problem, not a stale image problem.
  Never runs docker image prune / docker system prune — only jamoa-grid-jepsen* and
  volumes/containers from benchmarks/jepsen and benchmarks/jepsen/multidc compose projects.
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

function Remove-NamedVolumes([string[]]$Names) {
  foreach ($v in $Names) {
    docker volume rm -f $v 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
      Write-Host "removed volume $v"
    }
  }
}

Write-Host "=== jepsen-purge scope=$Scope purgeImages=$PurgeImages purgeM2=$PurgeM2 ==="
Write-Host "NOTE: OpLog Corrupt length=0 => wipe Jepsen DATA volumes only; never global docker prune."

if ($Scope -eq "1dc" -or $Scope -eq "all") {
  Invoke-ComposeDown $JEPSEN_DIR
  Remove-NamedVolumes @(
    "jepsen_n1-data", "jepsen_n2-data", "jepsen_n3-data",
    "benchmarks_jepsen_n1-data", "benchmarks_jepsen_n2-data", "benchmarks_jepsen_n3-data"
  )
}

if ($Scope -eq "multidc" -or $Scope -eq "all") {
  Invoke-ComposeDown $MULTIDC_DIR
  Remove-NamedVolumes @(
    "multidc_a1-data", "multidc_a2-data", "multidc_a3-data",
    "multidc_b1-data", "multidc_b2-data"
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