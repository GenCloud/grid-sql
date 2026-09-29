# Jepsen unclean-revive contour: long proposer down + start without mid-run purge.
# Purge only at compose up. Requires JEPSEN_UNCLEAN_REVIVE=1 for nemesis.clj path.
param(
  [int]$TimeLimit = 60,
  [switch]$SkipRebuild,
  [switch]$Fast,
  [int]$SettleSec = 0
)
$ErrorActionPreference = "Continue"
$env:JEPSEN_UNCLEAN_REVIVE = "1"
if (-not $env:JEPSEN_UNCLEAN_DOWN_SEC -or $env:JEPSEN_UNCLEAN_DOWN_SEC -eq "") {
  $env:JEPSEN_UNCLEAN_DOWN_SEC = "15"
}
if (-not $env:STAMP -or $env:STAMP -eq "") {
  $env:STAMP = (Get-Date -Format "yyyy-MM-dd") + "-jepsen-unclean-revive"
}
$env:JEPSEN_TIME_LIMIT = [string]$TimeLimit

$ScriptDir = $PSScriptRoot
$runFull = Join-Path $ScriptDir "run-jepsen.ps1"

if ($Fast -and $SettleSec -gt 0) {
  & $runFull -TimeLimit $TimeLimit -Fast -SkipRebuild:$SkipRebuild -SettleSec $SettleSec
} elseif ($Fast) {
  & $runFull -TimeLimit $TimeLimit -Fast -SkipRebuild:$true
} elseif ($SkipRebuild -and $SettleSec -gt 0) {
  & $runFull -TimeLimit $TimeLimit -SkipRebuild -SettleSec $SettleSec
} elseif ($SkipRebuild) {
  & $runFull -TimeLimit $TimeLimit -SkipRebuild
} elseif ($SettleSec -gt 0) {
  & $runFull -TimeLimit $TimeLimit -SettleSec $SettleSec
} else {
  & $runFull -TimeLimit $TimeLimit
}
$code = $LASTEXITCODE

$results = Join-Path (Resolve-Path (Join-Path $ScriptDir "..")).Path "RESULTS.md"
if (Test-Path $results) {
  $text = [IO.File]::ReadAllText($results)
  if ($text -notmatch "unclean-revive") {
    $text = $text -replace "(\| notes \| )([^\r\n]+)", ('$1unclean-revive downSec=' + $env:JEPSEN_UNCLEAN_DOWN_SEC + '; $2')
    $text = $text -replace "(\| chaos \| )([^\r\n]+)", '$1partition+unclean-revive'
    $text = $text -replace "(\| mode \| )([^\r\n]+)", '$1unclean-revive-jepsen'
    [IO.File]::WriteAllText($results, $text, [Text.UTF8Encoding]::new($false))
  }
}
exit $(if ($null -eq $code) { 1 } else { $code })