param([string]$Stamp = "2026-09-18-oss-peers")
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Results = Join-Path $Root "grid-server-core\benchmarks\lab"
$utf8 = [Text.UTF8Encoding]::new($false)
# Baseline ours from curated Grid JMH tables — named constants (internal lab only)
$OursPutUs = 14.650
$OursEqLimitUs = 1.262
$OursOrchidUs = 2394.4
$OursSyncVotersUs = 354.4
$ignitePath = Join-Path $Results "$Stamp-compare-ignite.json"
$igniteEmbedPath = Join-Path $Results "$Stamp-compare-ignite-embed.json"
$geodePath = Join-Path $Results "$Stamp-compare-geode.json"
$ignitePut = "n/a"; $igniteEmbedPut = "n/a"; $geodePut = "n/a"; $igniteMode = "n/a"
if (Test-Path $ignitePath) {
  $j = Get-Content $ignitePath -Raw | ConvertFrom-Json
  if ($null -ne $j.putP50Us) { $ignitePut = [string]::Format([Globalization.CultureInfo]::InvariantCulture, "{0:F3} us", [double]$j.putP50Us) }
  if ($null -ne $j.mode) { $igniteMode = [string]$j.mode }
}
if (Test-Path $igniteEmbedPath) {
  $j = Get-Content $igniteEmbedPath -Raw | ConvertFrom-Json
  if ($null -ne $j.putP50Us) { $igniteEmbedPut = [string]::Format([Globalization.CultureInfo]::InvariantCulture, "{0:F3} us", [double]$j.putP50Us) }
}
if (Test-Path $geodePath) {
  $j = Get-Content $geodePath -Raw | ConvertFrom-Json
  if ($null -ne $j.putP50Us) { $geodePut = [string]::Format([Globalization.CultureInfo]::InvariantCulture, "{0:F3} us", [double]$j.putP50Us) }
}
$md = @"
# Peer matrix (Ignite / Geode) — stamp ``$Stamp``

**Internal lab only — not product documentation.**

Baseline **ours** from curated Grid JMH constants (not re-run).
Peer numbers from ``run-compare-peers`` on this host.
Ignite peer = thin client / embedded.

| Track | Ours (baseline) | Ignite thin | Ignite embed | Geode | Notes |
|-------|-----------------|-------------|--------------|-------|-------|
| KV put p50 | $OursPutUs us | $ignitePut ($igniteMode) | $igniteEmbedPut | $geodePut | Geode gfsh = CLI overhead |
| EQ+LIMIT | $OursEqLimitUs us | n/a (thin SQL pending) | n/a | n/a | Same-func SQL peer pending |
| ORCHID durable | $OursOrchidUs us/op | n/a | n/a | n/a | No etcd-class twin |
| SYNC_VOTERS | $OursSyncVotersUs us | n/a | n/a | WAN TBD | Geode WAN gateway later |

Legacy HZ/Redis/etcd JSON stays under ``benchmarks/compare/`` or ``benchmarks/oss-peers/`` — never under capacity TOP stamps in ``grid-server-core/benchmarks/results/``.

"@
$outDir = Join-Path $Root "benchmarks\oss-peers"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$outEn = Join-Path $outDir "peer-matrix.en.md"
$outRu = Join-Path $outDir "peer-matrix.ru.md"
[IO.File]::WriteAllText($outEn, $md.Replace("`r`n","`n"), $utf8)
function J([int[]]$c){ -join ($c | ForEach-Object { [char]$_ }) }
$ruTitle = '# ' + (J 0x041C,0x0430,0x0442,0x0440,0x0438,0x0446,0x0430) + ' peers (Ignite / Geode) — `' + $Stamp + '`'
$ruNote = (J 0x0412,0x043D,0x0443,0x0442,0x0440,0x0435,0x043D,0x043D,0x044F,0x044F) + ' lab. Baseline ours: Grid JMH constants (not product docs).'
$ru = ($ruTitle + "`n`n" + $ruNote + "`n`n" + ($md -split "`n" | Select-Object -Skip 3 | Out-String))
[IO.File]::WriteAllText($outRu, $ru.Replace("`r`n","`n"), $utf8)
Write-Host "Wrote peer-matrix EN/RU under benchmarks/oss-peers (internal only)"