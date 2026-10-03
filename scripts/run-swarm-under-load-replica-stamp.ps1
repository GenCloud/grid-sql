param(
  [switch]$SkipBuild,
  [switch]$SkipWipe,
  [string]$StampPrefix = (Get-Date -Format "yyyy-MM-dd") + "-swarm-under-load",
  [int]$CoolSec = 45,
  [int]$ReadDur = 45,
  [int]$Clients = 32
)
# Calm HA stamp: READ_ONLY against replica GridUrl while apply-auto-cutover=true.
# Run alone on a calm host (do not co-run QG/Jepsen/JMH/other Load).
$ErrorActionPreference = "Stop"
$Utf8 = [Text.UTF8Encoding]::new($false)
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Starter = Join-Path $Root "grid-sql-server-starter"
$Results = Join-Path $Root "grid-server-core\benchmarks\lab"
$NotesPath = Join-Path $Results ($StampPrefix + "-notes.md")
$Jdk = Join-Path $env:USERPROFILE ".jdks\temurin-25"
if (Test-Path (Join-Path $Jdk "bin\java.exe")) {
  $env:JAVA_HOME = $Jdk
  $env:Path = "$(Join-Path $Jdk 'bin');" + $env:Path
}
$JavaOpts = "--enable-preview --add-modules=jdk.incubator.vector"
$CutoverArg = "--grid.replication.swarm.apply-auto-cutover=true"
$PrimaryGridUrl = "grid://grid:grid@127.0.0.1:15432/public"
$ReplicaGridUrl = "grid://grid:grid@127.0.0.1:15433/public"

function Write-Utf8([string]$Path, [string]$Content) {
  [IO.File]::WriteAllText($Path, $Content, $Utf8)
}

function Stop-HaJvms {
  Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -match "grid-sql-server-starter" -and $_.CommandLine -match "spring.profiles.active=(primary|replica)" } |
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

function Start-Ha {
  Set-Location $Starter
  if (-not $SkipWipe) {
    foreach ($d in @("data-primary", "data-replica")) {
      $p = Join-Path $Starter $d
      if (Test-Path $p) { Remove-Item -Recurse -Force $p }
    }
  }
  if (-not $SkipBuild) {
    Set-Location $Root
    mvn -pl grid-sql-server-starter -am package -DskipTests -q
    Set-Location $Starter
  }
  $jar = Get-ChildItem (Join-Path $Starter "target\grid-sql-server-starter-*.jar") | Select-Object -First 1
  if (-not $jar) { throw "starter jar missing - build first" }
  $logPrimary = Join-Path $Results ($StampPrefix + "-primary.out")
  $logReplica = Join-Path $Results ($StampPrefix + "-replica.out")
  New-Item -ItemType Directory -Force -Path $Results | Out-Null
  $pArgs = @("-jar", $jar.FullName, "--spring.profiles.active=primary", $CutoverArg)
  $rArgs = @("-jar", $jar.FullName, "--spring.profiles.active=replica", $CutoverArg)
  $env:JAVA_TOOL_OPTIONS = $JavaOpts
  Start-Process -FilePath "java" -ArgumentList $pArgs -WorkingDirectory $Starter -RedirectStandardOutput $logPrimary -RedirectStandardError ($logPrimary + ".err") -WindowStyle Hidden | Out-Null
  Start-Sleep -Seconds 4
  Start-Process -FilePath "java" -ArgumentList $rArgs -WorkingDirectory $Starter -RedirectStandardOutput $logReplica -RedirectStandardError ($logReplica + ".err") -WindowStyle Hidden | Out-Null
  if (-not (Wait-Health 7777 "/health/liveness")) { throw "primary liveness timeout" }
  if (-not (Wait-Health 7778 "/health/liveness")) { throw "replica liveness timeout" }
  if (-not (Wait-Health 7777 "/health/readiness" 180)) { throw "primary readiness timeout (orchid)" }
  if (-not (Wait-Health 7778 "/health/readiness" 180)) { throw "replica readiness timeout (orchid)" }
  Write-Host "HA UP + ready; READ stamp uses replica :15433"
  Start-Sleep -Seconds 15
}

Stop-HaJvms
try {
  Start-Ha
  Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
  $Stamp = $StampPrefix + "-replica-read"
  Write-Host "=== $Stamp clients=$Clients duration=${ReadDur}s mix=READ_ONLY GridUrl=$ReplicaGridUrl ==="
  $loadScript = Join-Path $Root "scripts\run-jmeter-load-slo.ps1"
  & $loadScript `
    -Stamp $Stamp -Clients $Clients -DurationSec $ReadDur `
    -MixProfile READ_ONLY -Profile capacity `
    -GridUrl "$ReplicaGridUrl" `
    -SetupGridUrl "$PrimaryGridUrl" `
    -ReadReplicaSession `
    -TpsFloor 400 `
    -SkipBuild
  $code = $LASTEXITCODE
  $notes = @"
# Swarm under load (replica READ)

- Stamp: $Stamp
- GridUrl: $ReplicaGridUrl (replica SQL, READ_REPLICA session)
- SetupGridUrl: $PrimaryGridUrl (DDL/seed on primary)
- apply-auto-cutover: true (CLI)
- Mix: READ_ONLY ${ReadDur}s clients=$Clients
- Gate: err=0 + modest TPS floor 400 (not living primary READ >=52261)
- Exit: $code
- LoadGate: high applyLag suppresses migrate I/O; QUIESCE fences shard writes via isDraining.
"@
  Write-Utf8 $NotesPath $notes
  Write-Host "notes -> $NotesPath"
  exit $code
} finally {
  Stop-HaJvms
}