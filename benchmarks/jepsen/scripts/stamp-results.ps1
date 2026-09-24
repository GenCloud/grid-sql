$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Repo = (Resolve-Path (Join-Path $Root "../..")).Path
$Results = Join-Path $Root "RESULTS.md"

$Stamp = if ($env:STAMP) { $env:STAMP } else { (Get-Date -Format "yyyy-MM-dd") + "-jepsen-smoke" }
$Mode = if ($env:MODE) { $env:MODE } else { "smoke" }
$Outcome = if ($env:OUTCOME) { $env:OUTCOME } else { "HARNESS_READY" }
$ComposeStatus = if ($env:COMPOSE_STATUS) { $env:COMPOSE_STATUS } else { "validated" }
$Chaos = if ($env:CHAOS) { $env:CHAOS } else { "skipped" }
$Full = if ($env:FULL) { $env:FULL } else { "not-run" }
$Notes = if ($env:NOTES) { $env:NOTES } else { "" }
$Command = if ($env:COMMAND) { $env:COMMAND } else { "run-jepsen-smoke.ps1" }

try { $Git = (git -C $Repo rev-parse --short HEAD 2>$null) } catch { $Git = "unknown" }
if (-not $Git) { $Git = "unknown" }
$HostName = $env:COMPUTERNAME
$Date = Get-Date -Format "o"

$history = @()
if (Test-Path $Results) {
  $lines = Get-Content $Results
  $idx = 0..($lines.Count - 1) | Where-Object { $lines[$_] -match '^### ' } | Select-Object -First 1
  if ($null -ne $idx) {
    $history = $lines[$idx..($lines.Count - 1)]
  }
}

$header = @"
# Jepsen RESULTS

Stamp template - filled by ``scripts/run-jepsen-smoke.*`` or a full Jepsen run.

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | $Stamp |
| date | $Date |
| git | $Git |
| host | $HostName |
| mode | ``$Mode`` |
| outcome | ``$Outcome`` |
| notes | $Notes |

## History

"@

$block = @"

### $Stamp
- mode: $Mode
- outcome: $Outcome
- git: $Git
- compose: $ComposeStatus
- chaos-it: $Chaos
- full-jepsen: $Full
- command: $Command
- notes: $Notes
"@

$out = $header
if ($history.Count -gt 0) {
  $out += ($history -join "`n") + "`n"
}
$out += $block
[IO.File]::WriteAllText($Results, $out, [Text.UTF8Encoding]::new($false))
Write-Host "Wrote $Results stamp=$Stamp outcome=$Outcome"

$updateDocs = Join-Path $Repo "scripts\update-perf-results-docs.ps1"
if (Test-Path $updateDocs) {
  try {
    & $updateDocs
  } catch {
    Write-Host "WARN update-perf-results-docs failed: $_"
  }
}
