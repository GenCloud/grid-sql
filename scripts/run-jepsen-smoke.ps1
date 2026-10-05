# Forwards to local-only Jepsen PowerShell tree (not in git).
$local = if ($env:JAMOA_GRID_LOCAL) { $env:JAMOA_GRID_LOCAL } else { "D:\workspace\jamoa-grid-cache-local" }
$target = Join-Path $local "benchmarks\jepsen\scripts\run-smoke.ps1"
if (-not (Test-Path $target)) { throw "Missing $target — clone/setup jamoa-grid-cache-local" }
& $target @args
exit $LASTEXITCODE