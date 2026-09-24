<#
.SYNOPSIS
  Refresh root docs/*/perf-results.md redirects only.
.DESCRIPTION
  Product hubs docs/en|ru/performance/results.md are curated by hand.
  TOP load stamps live under grid-server-core/benchmarks/results/SUMMARY.md —
  this script does NOT write diaries or JMH dumps into that folder.
#>
param(
  [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"
$utf8 = [Text.UTF8Encoding]::new($false)

if (-not $RepoRoot) {
  $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$enRedirectPath = Join-Path $RepoRoot "docs\en\perf-results.md"
$ruRedirectPath = Join-Path $RepoRoot "docs\ru\perf-results.md"

$enRedirect = "# Performance results`r`n`r`nMoved to [performance/results.md](performance/results.md).`r`n"

function U([int[]]$cp) { -join ($cp | ForEach-Object { [char]$_ }) }
$ruTitle = '# ' + (U 0x0420,0x0435,0x0437,0x0443,0x043B,0x044C,0x0442,0x0430,0x0442,0x044B) + ' ' + (U 0x043F,0x0440,0x043E,0x0438,0x0437,0x0432,0x043E,0x0434,0x0438,0x0442,0x0435,0x043B,0x044C,0x043D,0x043E,0x0441,0x0442,0x0438)
$ruMoved = (U 0x041F,0x0435,0x0440,0x0435,0x043D,0x0435,0x0441,0x0435,0x043D,0x043E) + ' в [performance/results.md](performance/results.md).'
$ruRedirect = $ruTitle + "`r`n`r`n" + $ruMoved + "`r`n"

[IO.File]::WriteAllText($enRedirectPath, $enRedirect, $utf8)
[IO.File]::WriteAllText($ruRedirectPath, $ruRedirect, $utf8)
Write-Host "Wrote redirects $enRedirectPath / $ruRedirectPath (results/ untouched)"
