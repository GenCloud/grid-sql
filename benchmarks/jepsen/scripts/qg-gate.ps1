# Multi-percentile algorithm gate vs Ref B (sql-v11 best nochao).
# Hard: p50 + p95. Soft: p99 (warn, does not alone fail unless -StrictP99).
# PASS by p50 alone is forbidden on calm host.
#
# GITHUB_ACTIONS / -CiAdvisory: Ref B numbers are still printed honestly.
# p95 hard FAIL on shared CI runners is treated as advisory (not calm host) -
# living Ref B p95 floor is NOT raised; only p50 hard FAIL fails the job
# (median-path regression smoke). Calm-host / host-stamp runs keep full hard p95.
param(
  [Parameter(Mandatory=$true)][string]$RegisterHistory,
  [Parameter(Mandatory=$true)][string]$AppendHistory,
  [int]$WarmupSeconds = 10,
  [double]$Tol = 0.05,
  [double]$P99Tol = 0.15,
  [switch]$StrictP99,
  [int]$MinOkWrite = 0,
  [int]$MinOkAppend = 0,
  [switch]$CiAdvisory
)

$ErrorActionPreference = "Stop"
$ci = [Globalization.CultureInfo]::InvariantCulture

$advisory = $CiAdvisory.IsPresent
if (-not $advisory -and $env:GITHUB_ACTIONS -eq "true") { $advisory = $true }
if (-not $advisory -and $env:QG_GATE_CI_ADVISORY -eq "1") { $advisory = $true }

# Ref B (v11 best nochao absolute targets before tolerance) - living floor; do not raise for CI green.
$ref = @{
  write_p50 = 12.7
  write_p95 = 88.1
  write_p99 = 252.4
  append_p50 = 11.9
  append_p95 = 36.8
  append_p99 = 47.4
  txn_r_p95 = 25.0
  txn_r_p99 = 55.0
}

function Get-Latencies([string]$HistoryEdn, [string]$Workload) {
  $script = Join-Path $PSScriptRoot "latency-from-history.ps1"
  $out = & $script -HistoryEdn $HistoryEdn -Workload $Workload -WarmupSeconds $WarmupSeconds
  $map = @{ n = @{}; p50 = @{}; p95 = @{}; p99 = @{}; ok = 0; fail = 0 }
  foreach ($line in $out) {
    if ($line -match 'ok=(\d+).*fail=(\d+)|fail=(\d+).*ok=(\d+)') {
      if ($Matches[1]) { $map.ok = [int]$Matches[1]; $map.fail = [int]$Matches[2] }
      else { $map.fail = [int]$Matches[3]; $map.ok = [int]$Matches[4] }
    }
    if ($line -match '^(read|write|txn_r|txn_append):\s+n=(\d+)\s+p50=([0-9.]+)ms\s+p95=([0-9.]+)ms\s+p99=([0-9.]+)ms') {
      $op = $Matches[1]
      $map.n[$op] = [int]$Matches[2]
      $map.p50[$op] = [double]::Parse($Matches[3], $ci)
      $map.p95[$op] = [double]::Parse($Matches[4], $ci)
      $map.p99[$op] = [double]::Parse($Matches[5], $ci)
    }
  }
  return $map
}

function Limit([double]$base, [double]$t) { return $base * (1.0 + $t) }

$reg = Get-Latencies $RegisterHistory "register"
$app = Get-Latencies $AppendHistory "append"

$rows = @()
$hardFail = $false
$softFail = $false
$p50HardFail = $false

function Add-Check([string]$Name, [Nullable[double]]$Got, [double]$Base, [string]$Kind) {
  if ($null -eq $Got) {
    $script:rows += [pscustomobject]@{ Check=$Name; Got="n/a"; Limit="n/a"; Kind=$Kind; Result="SKIP" }
    return
  }
  $lim = if ($Kind -eq "hard") { Limit $Base $Tol } else { Limit $Base $P99Tol }
  $ok = [double]$Got -le $lim
  $res = if ($ok) { "PASS" } else { "FAIL" }
  if (-not $ok -and $Kind -eq "hard") {
    $script:hardFail = $true
    if ($Name -match 'p50') { $script:p50HardFail = $true }
  }
  if (-not $ok -and $Kind -eq "soft") { $script:softFail = $true }
  $script:rows += [pscustomobject]@{
    Check=$Name
    Got=([string]::Format($ci, "{0:F3}", $Got))
    Limit=([string]::Format($ci, "{0:F3}", $lim))
    Kind=$Kind
    Result=$res
  }
}

Add-Check "write p50" $reg.p50["write"] $ref.write_p50 "hard"
Add-Check "write p95" $reg.p95["write"] $ref.write_p95 "hard"
Add-Check "append p50" $app.p50["txn_append"] $ref.append_p50 "hard"
Add-Check "append p95" $app.p95["txn_append"] $ref.append_p95 "hard"
Add-Check "txn_r p95" $app.p95["txn_r"] $ref.txn_r_p95 "hard"

Add-Check "write p99" $reg.p99["write"] $ref.write_p99 "soft"
Add-Check "append p99" $app.p99["txn_append"] $ref.append_p99 "soft"
Add-Check "txn_r p99" $app.p99["txn_r"] $ref.txn_r_p99 "soft"

$nWrite = if ($reg.n.ContainsKey("write")) { $reg.n["write"] } else { 0 }
$nAppend = if ($app.n.ContainsKey("txn_append")) { $app.n["txn_append"] } else { 0 }
if ($MinOkWrite -gt 0 -and $nWrite -lt $MinOkWrite) {
  $hardFail = $true
  $p50HardFail = $true
  $rows += [pscustomobject]@{ Check="n write"; Got="$nWrite"; Limit="$MinOkWrite"; Kind="hard"; Result="FAIL" }
}
if ($MinOkAppend -gt 0 -and $nAppend -lt $MinOkAppend) {
  $hardFail = $true
  $p50HardFail = $true
  $rows += [pscustomobject]@{ Check="n append"; Got="$nAppend"; Limit="$MinOkAppend"; Kind="hard"; Result="FAIL" }
}

$modeNote = if ($advisory) { " mode=CI_ADVISORY (p95 fail does not exit 1; Ref B p95 living floor = calm host)" } else { " mode=HOST_HARD" }
Write-Output ("=== qg-gate Ref B (tol p50/p95=+$([int]($Tol*100))% p99=+$([int]($P99Tol*100))%)$modeNote ===")
Write-Output ("register write n={0} p50={1} p95={2} p99={3}" -f $nWrite, $reg.p50["write"], $reg.p95["write"], $reg.p99["write"])
Write-Output ("append n={0} p50={1} p95={2} p99={3}" -f $nAppend, $app.p50["txn_append"], $app.p95["txn_append"], $app.p99["txn_append"])
Write-Output ("txn_r n={0} p50={1} p95={2} p99={3}" -f $app.n["txn_r"], $app.p50["txn_r"], $app.p95["txn_r"], $app.p99["txn_r"])
$rows | Format-Table -AutoSize | Out-String | Write-Output

$overall = "PASS"
if ($hardFail) { $overall = "FAIL" }
elseif ($StrictP99 -and $softFail) { $overall = "FAIL" }
elseif ($softFail) { $overall = "PASS_WITH_P99_WARN" }

if ($advisory -and $hardFail -and -not $p50HardFail) {
  $overall = "CI_ADVISORY_P95_FAIL"
  Write-Output ("OVERALL={0}" -f $overall)
  Write-Output "NOTE: Ref B p95 living floor unchanged - re-stamp on calm host. Do not raise Ref B for CI green."
  exit 0
}

Write-Output ("OVERALL={0}" -f $overall)
if ($overall -eq "FAIL") { exit 1 }
exit 0
