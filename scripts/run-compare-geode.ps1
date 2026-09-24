param([string]$Stamp = "")
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Peers = Join-Path $Root "benchmarks\oss-peers"
$Results = Join-Path $Root "grid-server-core\benchmarks\lab"
New-Item -ItemType Directory -Force -Path $Results | Out-Null
if (-not $Stamp) { $Stamp = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { Get-Date -Format "yyyy-MM-dd" } }
$Ops = if ($env:COMPARE_OPS) { [int]$env:COMPARE_OPS } else { 50 }
Write-Host "=== run-compare-geode stamp=$Stamp (gfsh put loop) ==="
Push-Location $Peers
try {
  docker compose up -d geode-locator geode-server | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "docker compose up geode failed" }
} finally { Pop-Location }
Start-Sleep -Seconds 90
$samples = New-Object System.Collections.Generic.List[double]
for ($i = 0; $i -lt $Ops; $i++) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  docker exec jamoa-oss-geode-server gfsh -e "connect --locator=geode-locator[10334]" -e "put --key=k$i --value=xxxxxxxx --region=/test" 2>$null | Out-Null
  $sw.Stop()
  $samples.Add($sw.Elapsed.TotalMilliseconds * 1000.0)
}
$sorted = $samples | Sort-Object
$p50 = $sorted[[Math]::Floor(($sorted.Count - 1) * 0.5)]
$avg = ($samples | Measure-Object -Average).Average
$utf8 = [Text.UTF8Encoding]::new($false)
$json = "{`"system`":`"geode`",`"mode`":`"gfsh-put`",`"ops`":$Ops,`"putP50Us`":$([string]::Format([Globalization.CultureInfo]::InvariantCulture,'{0:F3}',$p50)),`"putAvgUs`":$([string]::Format([Globalization.CultureInfo]::InvariantCulture,'{0:F3}',$avg)),`"stamp`":`"$Stamp`",`"client`":`"docker-gfsh`",`"notes`":`"region /test may need create; high latency includes gfsh CLI overhead`"}"
[IO.File]::WriteAllText((Join-Path $Results "$Stamp-compare-geode.json"), $json, $utf8)
Write-Host "Geode compare wrote $Stamp-compare-geode.json p50Us=$p50"