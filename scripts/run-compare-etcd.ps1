$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Compare = Join-Path $Root "benchmarks\compare"
$Results = Join-Path $Root "grid-server-core\benchmarks\lab"
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$Stamp = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { Get-Date -Format "yyyy-MM-dd" }
$Ops = if ($env:COMPARE_OPS) { [int]$env:COMPARE_OPS } else { 200 }
$Endpoint = if ($env:ETCD_ENDPOINTS) { $env:ETCD_ENDPOINTS } else { "http://127.0.0.1:2379" }

$EtcdCtl = Get-ChildItem -Path (Join-Path $Root ".tools\etcd") -Recurse -Filter etcdctl.exe -ErrorAction SilentlyContinue |
  Select-Object -First 1 -ExpandProperty FullName
if (-not $EtcdCtl) {
  throw "etcdctl.exe missing under .tools/etcd"
}

Set-Location $Compare
# docker compose prints "Container … Running" to stderr; do not treat as fatal under Stop
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker compose up -d etcd1 | Out-Null
$composeExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($null -ne $composeExit -and $composeExit -ne 0) {
  throw "docker compose up etcd1 failed: $composeExit"
}
Start-Sleep -Seconds 3

$env:ETCDCTL_API = "3"
$samples = New-Object System.Collections.Generic.List[double]
$value = "xxxxxxxx"
for ($i = 0; $i -lt $Ops; $i++) {
  $key = "k$i"
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  & $EtcdCtl --endpoints=$Endpoint put $key $value | Out-Null
  $sw.Stop()
  $samples.Add($sw.Elapsed.TotalMilliseconds * 1000.0)
}

$sorted = $samples | Sort-Object
$p50 = $sorted[[math]::Floor(($sorted.Count - 1) * 0.50)]
$p99 = $sorted[[math]::Floor(($sorted.Count - 1) * 0.99)]
$avg = ($samples | Measure-Object -Average).Average

$json = @{
  system = "etcd"
  mode = "1-node-put"
  endpoints = $Endpoint
  ops = $Ops
  payloadBytes = 8
  avgUs = [math]::Round($avg, 3)
  p50Us = [math]::Round($p50, 3)
  p99Us = [math]::Round($p99, 3)
  stamp = $Stamp
  client = "host-etcdctl"
} | ConvertTo-Json

$out = Join-Path $Results "$Stamp-compare-etcd.json"
Set-Content -Encoding UTF8 -Path $out -Value $json
Write-Host $json
Write-Host "Wrote $out"
