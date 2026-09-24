$ErrorActionPreference = "Continue"
$Ops = if ($env:CLUSTER_OPS) { [int]$env:CLUSTER_OPS } else { 50 }
$Base = if ($env:CLUSTER_BASE) { $env:CLUSTER_BASE } else { "http://127.0.0.1" }
$Stamp = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { (Get-Date -Format "yyyy-MM-dd") + "-cluster" }
$OutDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..\grid-server-core\benchmarks\lab")).Path ""
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Wait-Healthy([string]$Url, [int]$Sec = 180) {
  $deadline = (Get-Date).AddSeconds($Sec)
  while ((Get-Date) -lt $deadline) {
    try {
      $s = Invoke-RestMethod "$Url/health/liveness" -TimeoutSec 2
      if ($s.orchidSynced -eq $true) { return $s }
    } catch {}
    Start-Sleep -Seconds 2
  }
  throw "not healthy: $Url"
}

Write-Host "Waiting for n1/n2/n3 ..."
$s1 = Wait-Healthy "$Base`:7777"
$s2 = Wait-Healthy "$Base`:7778"
$s3 = Wait-Healthy "$Base`:7779"
Write-Host ("n1 synced={0} seq={1} proposer={2}" -f $s1.orchidSynced, $s1.lastCommittedSeq, $s1.phaseRankedProposer)
Write-Host ("n2 synced={0} seq={1}" -f $s2.orchidSynced, $s2.lastCommittedSeq)
Write-Host ("n3 synced={0} seq={1}" -f $s3.orchidSynced, $s3.lastCommittedSeq)

$writeUs = New-Object System.Collections.Generic.List[double]
$visN2Ms = New-Object System.Collections.Generic.List[double]
$visN3Ms = New-Object System.Collections.Generic.List[double]
$missN2 = 0
$missN3 = 0
$baseKey = [int](Get-Random -Minimum 100000 -Maximum 900000)

for ($i = 0; $i -lt $Ops; $i++) {
  $key = $baseKey + $i
  $body = @{ value = ("v-" + $i) } | ConvertTo-Json -Compress
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  try {
    $resp = Invoke-RestMethod -Method Put -Uri "$Base`:7777/jepsen/register/$key" -ContentType "application/json" -Body $body -TimeoutSec 15
    if ($resp.error) { Write-Host "write error key=$key $($resp.error) $($resp.message)"; continue }
  } catch {
    Write-Host "write fail key=$key : $($_.Exception.Message)"
    continue
  }
  $sw.Stop()
  $writeUs.Add($sw.Elapsed.TotalMilliseconds * 1000.0)

  $t0 = [DateTime]::UtcNow
  $seen2 = $false
  $seen3 = $false
  for ($p = 0; $p -lt 100; $p++) {
    if (-not $seen2) {
      try {
        $r = Invoke-RestMethod "$Base`:7778/jepsen/register/$key" -TimeoutSec 2
        if ($r.value -eq ("v-" + $i)) {
          $visN2Ms.Add(([DateTime]::UtcNow - $t0).TotalMilliseconds)
          $seen2 = $true
        }
      } catch {}
    }
    if (-not $seen3) {
      try {
        $r = Invoke-RestMethod "$Base`:7779/jepsen/register/$key" -TimeoutSec 2
        if ($r.value -eq ("v-" + $i)) {
          $visN3Ms.Add(([DateTime]::UtcNow - $t0).TotalMilliseconds)
          $seen3 = $true
        }
      } catch {}
    }
    if ($seen2 -and $seen3) { break }
    Start-Sleep -Milliseconds 20
  }
  if (-not $seen2) { $missN2++ }
  if (-not $seen3) { $missN3++ }
}

function Pct([System.Collections.Generic.List[double]]$xs, [double]$p) {
  if ($xs.Count -eq 0) { return $null }
  $sorted = $xs | Sort-Object
  return $sorted[[math]::Floor(($sorted.Count - 1) * $p)]
}

$json = [ordered]@{
  stamp = $Stamp
  system = "jamoa-grid"
  mode = "docker-3node-register"
  ops = $Ops
  write_p50_us = Pct $writeUs 0.50
  write_p99_us = Pct $writeUs 0.99
  write_avg_us = if ($writeUs.Count) { ($writeUs | Measure-Object -Average).Average } else { $null }
  visibility_n2_p50_ms = Pct $visN2Ms 0.50
  visibility_n2_p99_ms = Pct $visN2Ms 0.99
  visibility_n3_p50_ms = Pct $visN3Ms 0.50
  visibility_n3_p99_ms = Pct $visN3Ms 0.99
  miss_n2 = $missN2
  miss_n3 = $missN3
}
$pathOut = Join-Path $OutDir "$Stamp-cluster-3node-docker.json"
($json | ConvertTo-Json -Depth 6) | Set-Content -Path $pathOut -Encoding utf8
Write-Host "Wrote $pathOut"
Write-Host ("write p50={0:N0} us p99={1:N0} us | vis n2 p50={2:N1} ms n3 p50={3:N1} ms | miss {4}/{5}" -f `
  $json.write_p50_us, $json.write_p99_us, $json.visibility_n2_p50_ms, $json.visibility_n3_p50_ms, $missN2, $missN3)