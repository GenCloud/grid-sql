param(
  [switch]$SkipBuild,
  [switch]$SkipWipe,
  [string]$StampPrefix = (Get-Date -Format "yyyy-MM-dd") + "-dual-read-cap",
  [int]$CoolSec = 60,
  [int]$ReadDur = 45,
  [int]$RampSec = 5,
  # Living READ starts at 64; sweep finds host ceiling with errRate=0.
  # Pass as comma string from CLI: -ClientLevelsCsv "64,96,128,160"
  [string]$ClientLevelsCsv = "64,96,128,160",
  [switch]$NoCutover,
  # Ring >=3: start replica2 (15434) and READ_REPLICA URL lists all SQL ports (least-inflight).
  [switch]$Ring3
)
$ClientLevels = @(
  $ClientLevelsCsv.Split(@(',', ';', ' '), [StringSplitOptions]::RemoveEmptyEntries) |
    ForEach-Object { [int]$_.Trim() }
)
if ($ClientLevels.Count -lt 1) { throw "ClientLevelsCsv empty" }
# Calm dual/multi READ capacity: PRIMARY proposer vs READ_REPLICA least-inflight ring.
# Same mix/duration/clients; require errorRate=0. TpsFloor=1 so stamp records
# hardware TPS without living READ floor gate. Run alone (no QG/Jepsen/JMH).
# -Ring3: writer + 2 replicas (SQL 15432/15433/15434); floor from first calm series.
$ErrorActionPreference = "Stop"
$Utf8 = [Text.UTF8Encoding]::new($false)
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Starter = Join-Path $Root "grid-sql-server-starter"
$Results = Join-Path $Root "grid-server-core\benchmarks\lab"
$NotesPath = Join-Path $Results ($StampPrefix + "-notes.md")
$SummaryPath = Join-Path $Results ($StampPrefix + "-summary.json")
$Jdk = Join-Path $env:USERPROFILE ".jdks\temurin-25"
if (Test-Path (Join-Path $Jdk "bin\java.exe")) {
  $env:JAVA_HOME = $Jdk
  $env:Path = "$(Join-Path $Jdk 'bin');" + $env:Path
}
$JavaOpts = "--enable-preview --add-modules=jdk.incubator.vector"
$CutoverArg = "--grid.replication.swarm.apply-auto-cutover=true"
$PrimaryGridUrl = "grid://grid:grid@127.0.0.1:15432/public"
if ($Ring3) {
  $ReplicaGridUrl = "grid://grid:grid@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433,127.0.0.1:15434&readPreference=REPLICA&maxReadConnections=6"
} else {
  $ReplicaGridUrl = "grid://grid:grid@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433&readPreference=REPLICA&maxReadConnections=4"
}
# Discovery gate: pass on err=0 only (ignore living READ floor 52261).
$DiscoveryTpsFloor = 1.0

function Write-Utf8([string]$Path, [string]$Content) {
  [IO.File]::WriteAllText($Path, $Content, $Utf8)
}

function Stop-HaJvms {
  Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -match "grid-sql-server-starter" -and $_.CommandLine -match "spring.profiles.active=(primary|replica2?)" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 3
}

function Wait-Health([int]$Port, [string]$Path = "/health/liveness", [int]$TimeoutSec = 180) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  do {
    try {
      $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port$Path" -UseBasicParsing -TimeoutSec 3
      if ($r.StatusCode -eq 200) { return $true }
    } catch { }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  return $false
}

function Start-Ha([string]$LogPrefix) {
  Set-Location $Starter
  if (-not $SkipWipe) {
    $dirs = @("data-primary", "data-replica")
    if ($Ring3) { $dirs += "data-replica2" }
    foreach ($d in $dirs) {
      $p = Join-Path $Starter $d
      if (Test-Path $p) { Remove-Item -Recurse -Force $p }
    }
  }
  $jar = Get-ChildItem (Join-Path $Starter "target\grid-sql-server-starter-*.jar") | Select-Object -First 1
  if (-not $jar) { throw "starter jar missing - build first" }
  $logPrimary = Join-Path $Results ($LogPrefix + "-primary.out")
  $logReplica = Join-Path $Results ($LogPrefix + "-replica.out")
  New-Item -ItemType Directory -Force -Path $Results | Out-Null
  $pArgs = @("-jar", $jar.FullName, "--spring.profiles.active=primary")
  $rArgs = @("-jar", $jar.FullName, "--spring.profiles.active=replica")
  if (-not $NoCutover) {
    $pArgs += $CutoverArg
    $rArgs += $CutoverArg
  }
  $env:JAVA_TOOL_OPTIONS = $JavaOpts
  Start-Process -FilePath "java" -ArgumentList $pArgs -WorkingDirectory $Starter -RedirectStandardOutput $logPrimary -RedirectStandardError ($logPrimary + ".err") -WindowStyle Hidden | Out-Null
  Start-Sleep -Seconds 4
  Start-Process -FilePath "java" -ArgumentList $rArgs -WorkingDirectory $Starter -RedirectStandardOutput $logReplica -RedirectStandardError ($logReplica + ".err") -WindowStyle Hidden | Out-Null
  if ($Ring3) {
    $logReplica2 = Join-Path $Results ($LogPrefix + "-replica2.out")
    $r2Args = @("-jar", $jar.FullName, "--spring.profiles.active=replica2")
    if (-not $NoCutover) { $r2Args += $CutoverArg }
    Start-Sleep -Seconds 2
    Start-Process -FilePath "java" -ArgumentList $r2Args -WorkingDirectory $Starter -RedirectStandardOutput $logReplica2 -RedirectStandardError ($logReplica2 + ".err") -WindowStyle Hidden | Out-Null
  }
  if (-not (Wait-Health 7777 "/health/liveness")) { throw "primary liveness timeout" }
  if (-not (Wait-Health 7778 "/health/liveness")) { throw "replica liveness timeout" }
  if ($Ring3 -and -not (Wait-Health 7779 "/health/liveness")) { throw "replica2 liveness timeout" }
  if (-not (Wait-Health 7777 "/health/readiness" 180)) { throw "primary readiness timeout (orchid)" }
  if (-not (Wait-Health 7778 "/health/readiness" 180)) { throw "replica readiness timeout (orchid)" }
  if ($Ring3 -and -not (Wait-Health 7779 "/health/readiness" 180)) { throw "replica2 readiness timeout (orchid)" }
  Write-Host "HA UP + ready ring=$(if ($Ring3) { 3 } else { 2 }) cutover=$(-not $NoCutover)"
  Start-Sleep -Seconds 15
}

function Read-StampJson([string]$Stamp) {
  $json = Join-Path $Results "$Stamp-load-slo.json"
  if (-not (Test-Path $json)) { return $null }
  return (Get-Content $json -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Invoke-ReadGate(
  [string]$Stamp,
  [int]$Clients,
  [string]$GridUrl,
  [string]$SetupUrl,
  [switch]$Replica
) {
  Write-Host "=== $Stamp clients=$Clients duration=${ReadDur}s mix=READ_ONLY url=$GridUrl replica=$Replica ==="
  Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
  $loadScript = Join-Path $Root "scripts\run-jmeter-load-slo.ps1"
  $args = @{
    Stamp              = $Stamp
    Clients            = $Clients
    DurationSec        = $ReadDur
    RampSec            = $RampSec
    MixProfile         = "READ_ONLY"
    Profile            = "capacity"
    GridUrl            = $GridUrl
    TpsFloor           = $DiscoveryTpsFloor
    ErrorRateCeiling   = 0.0
    SkipBuild          = $true
    NoHtmlReport       = $true
  }
  if ($SetupUrl) { $args.SetupGridUrl = $SetupUrl }
  if ($Replica) { $args.ReadReplicaSession = $true }
  & $loadScript @args
  $code = $LASTEXITCODE
  $j = Read-StampJson $Stamp
  Write-Host "cool ${CoolSec}s after $Stamp (exit=$code)..."
  Start-Sleep -Seconds $CoolSec
  return [pscustomobject]@{
    stamp     = $Stamp
    role      = $(if ($Replica) { "READ_REPLICA" } else { "PRIMARY" })
    clients   = $Clients
    exitCode  = $code
    outcome   = $(if ($j) { $j.outcome } else { "MISSING" })
    tps       = $(if ($j) { [double]$j.tps } else { 0 })
    p50Us     = $(if ($j) { $j.p50Us } else { $null })
    p95Us     = $(if ($j) { $j.p95Us } else { $null })
    p99Us     = $(if ($j) { $j.p99Us } else { $null })
    errorRate = $(if ($j) { [double]$j.errorRate } else { 1 })
    samples   = $(if ($j) { $j.samples } else { 0 })
    gridUrl   = $GridUrl
  }
}

Stop-HaJvms
try {
  if (-not $SkipBuild) {
    Set-Location $Root
    Write-Host "Building starter + jmeter..."
    mvn -B -pl grid-sql-server-starter,grid-sql-jmeter -am package "-DskipTests"
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
  }

  $rows = New-Object System.Collections.Generic.List[object]
  $cutoverNote = if ($NoCutover) { "apply-auto-cutover=false (capacity-clean)" } else { "apply-auto-cutover=true CLI" }

  # --- PRIMARY proposer READ sweep ---
  Start-Ha ($StampPrefix + "-primary-ha")
  foreach ($c in $ClientLevels) {
    $stamp = "$StampPrefix-primary-c$c"
    $rows.Add((Invoke-ReadGate -Stamp $stamp -Clients $c -GridUrl $PrimaryGridUrl -SetupUrl "" ))
  }

  # Fresh HA before replica sweep (fair seed / catch-up).
  Stop-HaJvms
  Start-Sleep -Seconds 5
  Start-Ha ($StampPrefix + "-replica-ha")
  foreach ($c in $ClientLevels) {
    $stamp = "$StampPrefix-replica-c$c"
    $rows.Add((Invoke-ReadGate -Stamp $stamp -Clients $c -GridUrl $ReplicaGridUrl -SetupUrl $PrimaryGridUrl -Replica))
  }

  $primaryOk = @($rows | Where-Object { $_.role -eq "PRIMARY" -and $_.errorRate -eq 0 -and $_.tps -gt 0 })
  $replicaOk = @($rows | Where-Object { $_.role -eq "READ_REPLICA" -and $_.errorRate -eq 0 -and $_.tps -gt 0 })
  $primaryBest = $primaryOk | Sort-Object tps -Descending | Select-Object -First 1
  $replicaBest = $replicaOk | Sort-Object tps -Descending | Select-Object -First 1

  $rowMaps = @()
  foreach ($r in $rows) {
    $rowMaps += @{
      stamp = [string]$r.stamp; role = [string]$r.role; clients = [int]$r.clients
      exitCode = [int]$r.exitCode; outcome = [string]$r.outcome; tps = [double]$r.tps
      p50Us = $r.p50Us; p95Us = $r.p95Us; p99Us = $r.p99Us
      errorRate = [double]$r.errorRate; samples = $r.samples; gridUrl = [string]$r.gridUrl
    }
  }
  $summaryObj = @{
    stampPrefix = $StampPrefix
    readDurSec = $ReadDur
    coolSec = $CoolSec
    clientLevels = @($ClientLevels)
    cutover = [bool](-not $NoCutover)
    discoveryTpsFloor = $DiscoveryTpsFloor
    errorRateCeiling = 0
    rows = $rowMaps
    primaryBestTps = $(if ($primaryBest) { [double]$primaryBest.tps } else { 0 })
    primaryBestClients = $(if ($primaryBest) { [int]$primaryBest.clients } else { 0 })
    replicaBestTps = $(if ($replicaBest) { [double]$replicaBest.tps } else { 0 })
    replicaBestClients = $(if ($replicaBest) { [int]$replicaBest.clients } else { 0 })
  }
  Write-Utf8 $SummaryPath ($summaryObj | ConvertTo-Json -Depth 6)

  $sb = New-Object System.Text.StringBuilder
  $ringNote = if ($Ring3) { "ring=3 (15432+15433+15434 least-inflight)" } else { "ring=2 (15432+15433)" }
  [void]$sb.AppendLine("# Dual/multi READ capacity ($StampPrefix)")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("- Mix: READ_ONLY ${ReadDur}s, capacity profile")
  [void]$sb.AppendLine("- Gate: errorRate=0; TpsFloor=$DiscoveryTpsFloor (discovery, not living 52261)")
  [void]$sb.AppendLine("- $cutoverNote")
  [void]$sb.AppendLine("- $ringNote")
  [void]$sb.AppendLine("- READ_REPLICA URL: $ReplicaGridUrl")
  [void]$sb.AppendLine("- Clients: $($ClientLevels -join ', ')")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("| Role | Clients | TPS | p50us | p95us | p99us | errRate | outcome |")
  [void]$sb.AppendLine("|------|---------|-----|-------|-------|-------|---------|---------|")
  foreach ($r in $rows) {
    [void]$sb.AppendLine("| $($r.role) | $($r.clients) | $([Math]::Round($r.tps,1)) | $($r.p50Us) | $($r.p95Us) | $($r.p99Us) | $($r.errorRate) | $($r.outcome) |")
  }
  [void]$sb.AppendLine("")
  if ($primaryBest) {
    [void]$sb.AppendLine("- **PRIMARY ceiling (err=0):** $($primaryBest.tps) TPS @ $($primaryBest.clients) clients")
  } else {
    [void]$sb.AppendLine("- **PRIMARY ceiling:** none with err=0")
  }
  if ($replicaBest) {
    [void]$sb.AppendLine("- **READ_REPLICA ceiling (err=0):** $($replicaBest.tps) TPS @ $($replicaBest.clients) clients (host floor until raised)")
  } else {
    [void]$sb.AppendLine("- **READ_REPLICA ceiling:** none with err=0")
  }
  Write-Utf8 $NotesPath $sb.ToString()
  Write-Host "notes -> $NotesPath"
  Write-Host "summary -> $SummaryPath"

  $anyErr = @($rows | Where-Object { $_.errorRate -gt 0 }).Count -gt 0
  if ($anyErr) { exit 2 }
  exit 0
} finally {
  Stop-HaJvms
}
