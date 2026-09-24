$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot "..\benchmarks\jepsen\scripts\run-smoke.ps1")
exit $LASTEXITCODE
