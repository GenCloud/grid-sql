param(
  [Parameter(Mandatory=$true)][string]$HistoryEdn,
  [string]$Workload = "register",
  [int]$WarmupSeconds = 10
)
$dir = Split-Path $HistoryEdn
$content = Get-Content $HistoryEdn -Raw
$matches = [regex]::Matches($content, '\{(?:[^{}]|\{[^{}]*\})*\}')
$pending = @{}
$latencies = @{
  read = [System.Collections.Generic.List[double]]::new()
  write = [System.Collections.Generic.List[double]]::new()
  txn_r = [System.Collections.Generic.List[double]]::new()
  txn_append = [System.Collections.Generic.List[double]]::new()
}
$counts = @{ ok=0; fail=0; info=0 }
$warmNs = [long]$WarmupSeconds * 1000000000L
foreach ($m in $matches) {
  $s = $m.Value
  if ($s -notmatch ':type\s+:(invoke|ok|fail|info)') { continue }
  $type = $Matches[1]
  if ($s -notmatch ':process\s+(\d+)') { continue }
  $proc = $Matches[1]
  if ($s -notmatch ':time\s+(\d+)') { continue }
  $time = [long]$Matches[1]
  if ($s -notmatch ':f\s+:(\w+)') { continue }
  $f = $Matches[1]
  if ($type -eq 'invoke') {
    $pending[$proc] = @{ time=$time; f=$f; s=$s }
  } elseif ($type -eq 'ok' -or $type -eq 'fail' -or $type -eq 'info') {
    $counts[$type]++
    if ($type -ne 'ok') { continue }
    if (-not $pending.ContainsKey($proc)) { continue }
    $inv = $pending[$proc]
    if ([long]$inv.time -lt $warmNs) { $pending.Remove($proc); continue }
    $latMs = ($time - [long]$inv.time) / 1e6
    $bucket = $inv.f
    if ($bucket -eq 'txn') {
      if ($inv.s -match ':append' -or $s -match ':append') { $bucket = 'txn_append' }
      else { $bucket = 'txn_r' }
    }
    if ($latencies.ContainsKey($bucket)) { $latencies[$bucket].Add($latMs) }
    $pending.Remove($proc)
  }
}
function Pct($list, $p) {
  if ($list.Count -eq 0) { return $null }
  $arr = $list.ToArray() | Sort-Object
  $idx = [Math]::Min($arr.Length-1, [Math]::Floor(($p/100.0)*($arr.Length-1)))
  return [Math]::Round($arr[$idx], 3)
}
function Report($name, $list) {
  if ($list.Count -eq 0) { return "$name`: n=0" }
  $ci = [System.Globalization.CultureInfo]::InvariantCulture
  return ("{0}: n={1} p50={2}ms p95={3}ms p99={4}ms" -f $name, $list.Count,
    ([double](Pct $list 50)).ToString("0.000", $ci),
    ([double](Pct $list 95)).ToString("0.000", $ci),
    ([double](Pct $list 99)).ToString("0.000", $ci))
}
Write-Output ("workload={0} warmupDrop={1}s fail={2} info={3} ok={4}" -f $Workload, $WarmupSeconds, $counts.fail, $counts.info, $counts.ok)
Write-Output (Report "read" $latencies.read)
Write-Output (Report "write" $latencies.write)
Write-Output (Report "txn_r" $latencies.txn_r)
Write-Output (Report "txn_append" $latencies.txn_append)