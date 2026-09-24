<#
.SYNOPSIS
  Apache JMeter load-SLO gate (TPS / p95 / p99 / error rate) via grid-sql-client WS samplers.

.DESCRIPTION
  Calm host only - never parallel with QG / Jepsen / OSS peers / JMH.
  Uses product grid:// RemoteConnectionFactory (never JDBC tooling / jdbc:grid://).
  Reports: JMeter Aggregate Report / Summary Report CSV + HTML Dashboard (-e -o).
  Stamp JSON is a thin SLO gate over the JTL (not a custom report engine).

.PARAMETER DrySmoke
  Build sampler + stage classpath + verify JMETER_HOME / JMX; skip JMeter run and SLO gate.
#>
param(
  [string]$Stamp = "",
  [int]$Clients = 128,
  [int]$DurationSec = 120,
  [int]$RampSec = 5,
  [string]$GridUrl = "grid://grid:grid@127.0.0.1:15432/public",
  [string]$User = "",
  [string]$Password = "",
  [double]$TpsFloor = 0,
  [double]$P95CeilingUs = 0,
  [double]$P99CeilingUs = 0,
  [double]$ErrorRateCeiling = 0,
  [string]$JMeterHome = "",
  [ValidateSet("contention", "capacity")]
  [string]$Profile = "capacity",
  [ValidateSet("CAPACITY", "CHAOS", "STRESS", "READ_ONLY", "WRITE_ONLY")]
  [string]$MixProfile = "CAPACITY",
  [int]$KeySpace = 0,
  [int]$OpTimeoutMs = 30000,
  [int]$WriteBatchSize = 1,
  [switch]$DrySmoke,
  [switch]$SkipBuild,
  [switch]$NoHtmlReport
)
$ErrorActionPreference = "Stop"
$Utf8NoBom = [Text.UTF8Encoding]::new($false)

$Script:DefaultTpsFloor = 400.0
# Living floors: WRITE ~95% of peak; READ = observed band min (host variance = band like QG)
# see docs/en/capacity-slo.md
$Script:LivingCanonWriteTpsFloor = 4676.0
$Script:LivingCanonReadTpsFloor = 52261.0
$Script:DefaultP95CeilingUs = 1.0e9
$Script:DefaultP99CeilingUs = 1.0e9
$Script:DefaultErrorRateCeiling = 0.0
$Script:DefaultJMeterHomeWindows = "d:\apache-jmeter-5.6.3"
$Script:MsToUs = 1000.0
$Script:DefaultJdk25 = Join-Path $env:USERPROFILE ".jdks\temurin-25"
$Script:ContentionKeySpace = 10000
$Script:CapacityKeySpace = 1000000

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
  if (Test-Path (Join-Path $Script:DefaultJdk25 "bin\javac.exe")) {
    $env:JAVA_HOME = $Script:DefaultJdk25
    $env:Path = "$(Join-Path $env:JAVA_HOME 'bin');" + $env:Path
  }
}

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Results = Join-Path $Root "grid-server-core\benchmarks\lab"
$ModuleDir = Join-Path $Root "grid-sql-jmeter"
$Jmx = Join-Path $ModuleDir "grid-sql-load.jmx"
$WorkDir = Join-Path $ModuleDir "target\jmeter-run"
New-Item -ItemType Directory -Force -Path $Results | Out-Null
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

if (-not $Stamp) {
  $Stamp = if ($env:JMH_STAMP) { $env:JMH_STAMP } else { (Get-Date -Format "yyyy-MM-dd") + "-load-slo" }
}
if ($env:LOAD_SLO_CLIENTS) { $Clients = [int]$env:LOAD_SLO_CLIENTS }
if ($env:LOAD_SLO_DURATION_SEC) { $DurationSec = [int]$env:LOAD_SLO_DURATION_SEC }
if ($env:LOAD_SLO_RAMP_SEC) { $RampSec = [int]$env:LOAD_SLO_RAMP_SEC }
if ($env:LOAD_SLO_GRID_URL) { $GridUrl = $env:LOAD_SLO_GRID_URL }
if ($env:LOAD_SLO_TPS_FLOOR) { $TpsFloor = [double]$env:LOAD_SLO_TPS_FLOOR }
if ($env:LOAD_SLO_P95_CEILING_US) { $P95CeilingUs = [double]$env:LOAD_SLO_P95_CEILING_US }
if ($env:LOAD_SLO_P99_CEILING_US) { $P99CeilingUs = [double]$env:LOAD_SLO_P99_CEILING_US }
if ($env:LOAD_SLO_ERROR_RATE_CEILING) { $ErrorRateCeiling = [double]$env:LOAD_SLO_ERROR_RATE_CEILING }

if ($TpsFloor -le 0) { $TpsFloor = $Script:DefaultTpsFloor }
if ($MixProfile -eq 'WRITE_ONLY' -and [math]::Abs($TpsFloor - $Script:DefaultTpsFloor) -lt 0.01) {
  $TpsFloor = $Script:LivingCanonWriteTpsFloor
}
if ($MixProfile -eq 'READ_ONLY' -and [math]::Abs($TpsFloor - $Script:DefaultTpsFloor) -lt 0.01) {
  $TpsFloor = $Script:LivingCanonReadTpsFloor
}
if ($P95CeilingUs -le 0) { $P95CeilingUs = $Script:DefaultP95CeilingUs }
if ($P99CeilingUs -le 0) { $P99CeilingUs = $Script:DefaultP99CeilingUs }
if ($ErrorRateCeiling -lt 0) { $ErrorRateCeiling = $Script:DefaultErrorRateCeiling }

if (-not $JMeterHome) {
  if ($env:JMETER_HOME) { $JMeterHome = $env:JMETER_HOME }
  else { $JMeterHome = $Script:DefaultJMeterHomeWindows }
}

function Get-PercentileUs {
  param([double[]]$SortedMs, [double]$Pct)
  if ($null -eq $SortedMs -or $SortedMs.Length -eq 0) { return $null }
  $n = $SortedMs.Length
  $rank = [Math]::Ceiling($Pct / 100.0 * $n) - 1
  if ($rank -lt 0) { $rank = 0 }
  if ($rank -ge $n) { $rank = $n - 1 }
  return [Math]::Round($SortedMs[$rank] * $Script:MsToUs, 3)
}

Write-Host "=== run-jmeter-load-slo stamp=$Stamp clients=$Clients duration=${DurationSec}s ==="
Write-Host "JMETER_HOME=$JMeterHome"
Write-Host "SLO: tpsFloor=$TpsFloor p95CeilingUs=$P95CeilingUs p99CeilingUs=$P99CeilingUs errorRateCeiling=$ErrorRateCeiling"
Write-Host "Reports: Aggregate/Summary Report CSV + JMeter HTML Dashboard (-e -o)"
Write-Host "Calm host only - do not co-run with QG / Jepsen / OSS peers / JMH."

if (-not (Test-Path $Jmx)) { throw "Missing JMX: $Jmx" }
if (-not (Test-Path $JMeterHome)) {
  Write-Warning "JMETER_HOME not found at $JMeterHome."
  if (-not $DrySmoke) { throw "JMETER_HOME missing: $JMeterHome" }
}

$JmeterBat = Join-Path $JMeterHome "bin\jmeter.bat"
$JarCandidate = Get-ChildItem -Path (Join-Path $ModuleDir "target") -Filter "grid-sql-jmeter-*.jar" -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notmatch "sources|javadoc|original" } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
$Jar = if ($JarCandidate) { $JarCandidate.FullName } else { Join-Path $ModuleDir "target\grid-sql-jmeter-1.0.jar" }
$DepDir = Join-Path $ModuleDir "target\dependency"
$ReportDir = Join-Path $WorkDir "$Stamp-reports"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null

if (-not $SkipBuild) {
  Write-Host "Building grid-sql-jmeter..."
  Push-Location $Root
  try {
    & mvn -pl grid-sql-jmeter -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed (exit $LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
}

if (-not (Test-Path $Jar)) { throw "Sampler jar missing: $Jar" }

$CpParts = New-Object System.Collections.Generic.List[string]
$CpParts.Add($Jar)
# JMeter ships log4j-slf4j-impl; Spring's log4j-to-slf4j on the same CP blows up AbstractJavaSamplerClient <clinit>.
$ExcludeDepPrefixes = @("log4j-to-slf4j", "log4j-slf4j-impl", "slf4j-reload4j", "slf4j-log4j12")
if (Test-Path $DepDir) {
  # Prefer non-SNAPSHOT artifacts when both 1.0 and 1.0-SNAPSHOT sit in dependency/ (version bump leftover).
  $byBase = @{}
  Get-ChildItem $DepDir -Filter "*.jar" | Where-Object {
    $base = $_.BaseName
    -not ($ExcludeDepPrefixes | Where-Object { $base.StartsWith($_) })
  } | ForEach-Object {
    $name = $_.Name
    $key = if ($name -match '^(.*?)-(\d+(?:\.\d+)*)(?:-SNAPSHOT)?\.jar$') { $Matches[1] } else { $_.BaseName }
    $isSnap = $name -match '-SNAPSHOT\.jar$'
    if (-not $byBase.ContainsKey($key)) {
      $byBase[$key] = $_
    } elseif ($isSnap -and $byBase[$key].Name -notmatch '-SNAPSHOT\.jar$') {
      # keep release
    } elseif (-not $isSnap) {
      $byBase[$key] = $_
    } elseif ($_.LastWriteTime -gt $byBase[$key].LastWriteTime) {
      $byBase[$key] = $_
    }
  }
  foreach ($item in ($byBase.Values | Sort-Object Name)) {
    $CpParts.Add($item.FullName)
  }
}
$UserClasspath = ($CpParts -join ";")

if ($DrySmoke) {
  $out = Join-Path $Results "$Stamp-load-slo.json"
  $json = @"
{
  "stamp": "$Stamp",
  "outcome": "DRY_SMOKE",
  "harness": "jmeter-ws-model",
  "clients": $Clients,
  "durationSec": $DurationSec,
  "tpsFloor": $TpsFloor,
  "p95CeilingUs": $P95CeilingUs,
  "p99CeilingUs": $P99CeilingUs,
  "errorRateCeiling": $ErrorRateCeiling,
  "tps": null,
  "p50Us": null,
  "p95Us": null,
  "p99Us": null,
  "errorRate": null,
  "jmeterHome": "$($JMeterHome -replace '\\','/')",
  "jmx": "grid-sql-jmeter/grid-sql-load.jmx",
  "samplerJar": "grid-sql-jmeter/target/grid-sql-jmeter-1.0-SNAPSHOT.jar",
  "classpathEntries": $($CpParts.Count),
  "reports": {
    "aggregateReport": "Aggregate Report (JMeter UI / CSV)",
    "summaryReport": "Summary Report (JMeter UI / CSV)",
    "htmlDashboard": "JMeter HTML Dashboard Report (-e -o)"
  },
  "notes": "Dry smoke: built sampler + staged classpath; skipped JMeter run / SLO gate. Calm host only."
}
"@
  [IO.File]::WriteAllText($out, $json, $Utf8NoBom)
  Write-Host "Dry smoke OK - wrote $out"
  exit 0
}

if (-not (Test-Path $JmeterBat)) { throw "Missing jmeter.bat: $JmeterBat" }

$Jtl = Join-Path $WorkDir "$Stamp-load-slo.jtl"
$JmeterLog = Join-Path $WorkDir "$Stamp-jmeter.log"
$HtmlDir = Join-Path $WorkDir "$Stamp-html-report"
if (Test-Path $Jtl) { Remove-Item -Force $Jtl }
if (Test-Path $HtmlDir) { Remove-Item -Recurse -Force $HtmlDir }

Write-Host "Running JMeter non-GUI..."
if ($KeySpace -le 0) {
  if ($Profile -eq "capacity") {
    $KeySpace = $Script:CapacityKeySpace
  } else {
    $KeySpace = $Script:ContentionKeySpace
  }
}
Write-Host "Profile=$Profile MixProfile=$MixProfile KEY_SPACE=$KeySpace (CLI -n; calm host only)"
$jmeterArgs = @(
  "-n",
  "-t", $Jmx,
  "-l", $Jtl,
  "-j", $JmeterLog,
  "-Juser.classpath=$UserClasspath",
  "-JcapacityThreads=$Clients",
  "-JcapacityDurationSec=$DurationSec",
  "-JcapacityRampSec=$RampSec",
  "-JGRID_URL=$GridUrl",
  "-JUSER=$User",
  "-JPASSWORD=$Password",
  "-JKEY_SPACE=$KeySpace",
  "-JOP_TIMEOUT_MS=$(if ($OpTimeoutMs -gt 0) { $OpTimeoutMs } else { 30000 })",
  "-JMIX_PROFILE=$MixProfile",
  "-JWRITE_BATCH_SIZE=$WriteBatchSize",
  "-JREPORT_DIR=$($ReportDir -replace '\\','/')"
)
# Optional SQL template overrides (empty = living defaults in GridSqlLoadSqlTemplates):
# -JTABLE_A=... -JSQL_EQ_LIMIT='SELECT ...' -JSQL_UPSERT='INSERT ...' etc.
# Placeholders: ${tableA} ${tableB} ${id} ${val} ${n}
if (-not $NoHtmlReport) {
  $jmeterArgs += @("-e", "-o", $HtmlDir)
}

# JMeter non-GUI often hangs after "... end of run". Never -Wait forever.
# Do not redirect console to disk (slows the client); detect finish via JTL quiescence + kill.
$Script:JmeterHardTimeoutSec = [Math]::Max(120, ($DurationSec + $RampSec + 90))
$proc = Start-Process -FilePath $JmeterBat -ArgumentList $jmeterArgs -WorkingDirectory (Join-Path $JMeterHome "bin") `
  -PassThru -NoNewWindow
$deadline = [DateTime]::UtcNow.AddSeconds($Script:JmeterHardTimeoutSec)
$sawEnd = $false
$jtlStableSince = $null
$lastJtlLen = -1
$minRunSec = $DurationSec + $RampSec + 8
while ([DateTime]::UtcNow -lt $deadline) {
  if ($proc.HasExited) { break }
  if (Test-Path $Jtl) {
    $len = (Get-Item $Jtl).Length
    if ($len -eq $lastJtlLen -and $len -gt 1024) {
      if ($null -eq $jtlStableSince) { $jtlStableSince = [DateTime]::UtcNow }
      elseif (([DateTime]::UtcNow - $jtlStableSince).TotalSeconds -ge 12 -and
              ([DateTime]::UtcNow - $proc.StartTime.ToUniversalTime()).TotalSeconds -ge $minRunSec) {
        $sawEnd = $true
        break
      }
    } else {
      $jtlStableSince = $null
      $lastJtlLen = $len
    }
  }
  Start-Sleep -Seconds 2
}
function Stop-JMeterJvms {
  Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
    $_.CommandLine -and ($_.CommandLine -match 'ApacheJMeter|jmeter\.jar')
  } | ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }
}
if (-not $proc.HasExited) {
  try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { }
  Stop-JMeterJvms
  Start-Sleep -Seconds 1
  Stop-JMeterJvms
  if ($sawEnd) {
    Write-Warning "JMeter hung after end of run - killed JVM; continuing gate from JTL"
  } else {
    Write-Warning "JMeter hard-timeout ${Script:JmeterHardTimeoutSec}s - killed; see $JmeterLog"
  }
} else {
  Stop-JMeterJvms
}
if ($proc.HasExited -and $proc.ExitCode -ne 0) {
  Write-Warning "JMeter exit code $($proc.ExitCode) - see $JmeterLog"
}

if (-not (Test-Path $Jtl)) { throw "JMeter did not write JTL: $Jtl" }

$elapsedMs = New-Object System.Collections.Generic.List[double]
$total = 0
$ok = 0
$fail = 0
# Stream JTL; reservoir-sample latencies (cap) so multi-million JTLs do not stall the gate.
$Script:JtlLatencyCap = 100000
$rng = [System.Random]::new(42)
$header = $null
$reader = [IO.StreamReader]::new($Jtl, [Text.UTF8Encoding]::new($false))
try {
  while ($null -ne ($line = $reader.ReadLine())) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($null -eq $header) {
      $header = $line.Split(",")
      continue
    }
    $parts = $line.Split(",")
    if ($parts.Length -lt 8) { continue }
    $label = $parts[2]
    if ($label -eq "grid-sql-setup" -or $label -like "*SETUP*") { continue }
    $total++
    $elapsed = 0.0
    [void][double]::TryParse($parts[1], [ref]$elapsed)
    $success = $parts[7]
    if ($success -eq "true" -or $success -eq "TRUE") {
      $ok++
      if ($elapsedMs.Count -lt $Script:JtlLatencyCap) {
        $elapsedMs.Add($elapsed)
      } else {
        $j = $rng.Next(0, $ok)
        if ($j -lt $Script:JtlLatencyCap) {
          $elapsedMs[$j] = $elapsed
        }
      }
    } else {
      $fail++
    }
  }
} finally {
  $reader.Dispose()
}

$durationForTps = [Math]::Max(1.0, [double]$DurationSec)
# WRITE_ONLY TX batch: one JTL row may represent WriteBatchSize upserts (SampleCount).
$opsPerSample = 1
if ($MixProfile -eq 'WRITE_ONLY' -and $WriteBatchSize -gt 1) {
  $opsPerSample = $WriteBatchSize
}
$tps = if ($ok -gt 0) { [Math]::Round(($ok * $opsPerSample) / $durationForTps, 3) } else { 0.0 }
$errorRate = if ($total -gt 0) { [Math]::Round(($fail / [double]$total), 6) } else { 1.0 }

$sorted = $elapsedMs.ToArray()
[Array]::Sort($sorted)
$p50Us = Get-PercentileUs -SortedMs $sorted -Pct 50
$p95Us = Get-PercentileUs -SortedMs $sorted -Pct 95
$p99Us = Get-PercentileUs -SortedMs $sorted -Pct 99

$passTps = $tps -ge $TpsFloor
$passP95 = ($null -ne $p95Us) -and ($p95Us -le $P95CeilingUs)
$passP99 = ($null -ne $p99Us) -and ($p99Us -le $P99CeilingUs)
$passErr = $errorRate -le $ErrorRateCeiling
$outcome = if ($passTps -and $passP95 -and $passP99 -and $passErr -and $total -gt 0) { "PASS" } else { "FAIL" }

$p50Json = if ($null -eq $p50Us) { "null" } else { "$p50Us" }
$p95Json = if ($null -eq $p95Us) { "null" } else { "$p95Us" }
$p99Json = if ($null -eq $p99Us) { "null" } else { "$p99Us" }
$htmlRel = if ((-not $NoHtmlReport) -and (Test-Path $HtmlDir)) { ($HtmlDir -replace '\\','/') } else { $null }
$htmlJson = if ($null -eq $htmlRel) { "null" } else { "`"$htmlRel`"" }

$out = Join-Path $Results "$Stamp-load-slo.json"
$json = @"
{
  "stamp": "$Stamp",
  "outcome": "$outcome",
  "harness": "jmeter-ws-model",
  "clients": $Clients,
  "durationSec": $DurationSec,
  "tpsFloor": $TpsFloor,
  "p95CeilingUs": $P95CeilingUs,
  "p99CeilingUs": $P99CeilingUs,
  "errorRateCeiling": $ErrorRateCeiling,
  "tps": $tps,
  "p50Us": $p50Json,
  "p95Us": $p95Json,
  "p99Us": $p99Json,
  "errorRate": $errorRate,
  "samples": $total,
  "successSamples": $ok,
  "failedSamples": $fail,
  "gridUrl": "$GridUrl",
  "jtl": "$($Jtl -replace '\\','/')",
  "reports": {
    "aggregateReportCsv": "$($ReportDir -replace '\\','/')/aggregate-report.csv",
    "summaryReportCsv": "$($ReportDir -replace '\\','/')/summary-report.csv",
    "htmlDashboard": $htmlJson,
    "uiListeners": ["Aggregate Report", "Summary Report", "Graph Results", "Response Time Graph"]
  },
  "notes": "Calm host only. Open plan in JMeter GUI for Aggregate/Summary Report. CLI HTML Dashboard via -e -o. Mix via GridSqlRequestResponseSampler (grid-sql-client)."
}
"@
[IO.File]::WriteAllText($out, $json, $Utf8NoBom)
Write-Host "Wrote $out outcome=$outcome tps=$tps p95Us=$p95Us p99Us=$p99Us errorRate=$errorRate"
if ($htmlRel) { Write-Host "HTML Dashboard: $htmlRel/index.html" }
Write-Host "Aggregate/Summary CSV: $ReportDir"
if ($outcome -ne "PASS") { exit 1 }
exit 0