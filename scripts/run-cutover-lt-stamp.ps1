param(
  [switch]$SkipBuild,
  [switch]$SkipWipe,
  [ValidateSet("living","plus60")]
  [string]$WindowMode = "living",
  [string]$StampPrefix = "2026-09-21-cutover-clean",
  [int]$CoolSec = 45,
  [switch]$RestartBetweenGates = $true
)
# Clean calm HA LT with apply-auto-cutover=true (CLI only).
# living = canon windows 40/45/45/120 for production decision vs living floors.
# Does NOT flip product YAML default. Does NOT raise floors in docs.
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
$GridUrl = "grid://grid:grid@127.0.0.1:15432/public"

# Living canons (capacity-slo) vs exploratory +60
if ($WindowMode -eq "living") {
  $WriteDur = 40; $ReadDur = 45; $QgDur = 45; $MixDur = 120
  $WindowLabel = "WRITE 40s / READ 45s / QG 45s / mix 120s (living canon)"
} else {
  $WriteDur = 100; $ReadDur = 105; $QgDur = 105; $MixDur = 180
  $WindowLabel = "WRITE 100s / READ 105s / QG 105s / mix 180s (canon+60)"
}

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
  $jar = Get-ChildItem (Join-Path $Starter "target\grid-sql-server-starter-*.jar") | Select-Object -First 1
  if (-not $jar) { throw "starter jar missing - build first" }
  $logPrimary = Join-Path $Results ($StampPrefix + "-primary.out")
  $logReplica = Join-Path $Results ($StampPrefix + "-replica.out")
  $pArgs = @("-jar", $jar.FullName, "--spring.profiles.active=primary", $CutoverArg)
  $rArgs = @("-jar", $jar.FullName, "--spring.profiles.active=replica", $CutoverArg)
  $env:JAVA_TOOL_OPTIONS = $JavaOpts
  Start-Process -FilePath "java" -ArgumentList $pArgs -WorkingDirectory $Starter -RedirectStandardOutput $logPrimary -RedirectStandardError ($logPrimary + ".err") -WindowStyle Hidden | Out-Null
  Start-Sleep -Seconds 4
  Start-Process -FilePath "java" -ArgumentList $rArgs -WorkingDirectory $Starter -RedirectStandardOutput $logReplica -RedirectStandardError ($logReplica + ".err") -WindowStyle Hidden | Out-Null
  if (-not (Wait-Health 7777 "/health/liveness")) { throw "primary liveness timeout" }
  if (-not (Wait-Health 7778 "/health/liveness")) { throw "replica liveness timeout" }
  # Wait ORCHID sync (readiness) before load - fail-closed admission
  if (-not (Wait-Health 7777 "/health/readiness" 180)) { throw "primary readiness timeout (orchid)" }
  if (-not (Wait-Health 7778 "/health/readiness" 180)) { throw "replica readiness timeout (orchid)" }
  Write-Host "HA primary+replica UP + ready (apply-auto-cutover=true CLI)"
  Start-Sleep -Seconds 15
}

function Run-Gate([string]$Stamp, [int]$Clients, [int]$DurationSec, [string]$Mix) {
  Write-Host "=== $Stamp clients=$Clients duration=${DurationSec}s mix=$Mix cool=${CoolSec}s ==="
  # Never leak server JAVA_TOOL_OPTIONS (--add-modules=jdk.incubator.vector) into JMeter client.
  Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
  & (Join-Path $Root "scripts\run-jmeter-load-slo.ps1") `
    -Stamp $Stamp -Clients $Clients -DurationSec $DurationSec `
    -MixProfile $Mix -Profile capacity -GridUrl $GridUrl -SkipBuild
  $code = $LASTEXITCODE
  if ($code -ne 0) {
    Write-Host "WARN gate exited $code (record stamp; production decision from notes)"
  }
  Write-Host "cool ${CoolSec}s..."
  Start-Sleep -Seconds $CoolSec
  return $code
}

function Read-StampJson([string]$Stamp) {
  $json = Join-Path $Results "$Stamp-load-slo.json"
  if (-not (Test-Path $json)) { return $null }
  return (Get-Content $json -Raw | ConvertFrom-Json)
}

try {
  if (-not $SkipBuild) {
    Set-Location $Root
    mvn -B -pl grid-sql-server-starter -am package "-DskipTests"
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
  }
  Stop-HaJvms
  Start-Ha
  try { $Git = (git -C $Root rev-parse --short HEAD 2>$null) } catch { $Git = "unknown" }

  $hdr = @"
# Cutover LT clean stamp ($StampPrefix)

| Field | Value |
|-------|--------|
| date | $(Get-Date -Format o) |
| git | $Git |
| host | $($env:COMPUTERNAME) |
| topology | primary+replica, fsync true |
| apply-auto-cutover | **true** (CLI only; product YAML default remains **false**) |
| windows | $WindowLabel |
| coolSec | $CoolSec |
| floors | living WRITE ~4676 / READ band ~52261…59430 (floor ~52261) / QG ~8333; mix sizing ref unchanged |
| purpose | calm living-capacity stamp for production cutover decision |

## Gates

"@
  Write-Utf8 $NotesPath $hdr

  $stamps = @(
    @{ Name = "$StampPrefix-write"; Clients = 48; Dur = $WriteDur; Mix = "WRITE_ONLY"; Floor = 4676; Living = 4921.975 },
    @{ Name = "$StampPrefix-read";  Clients = 64; Dur = $ReadDur;  Mix = "READ_ONLY";  Floor = 52261; Living = 59430.467 },
    @{ Name = "$StampPrefix-qg";    Clients = 64; Dur = $QgDur;    Mix = "CAPACITY";   Floor = 8333; Living = 11351.533 },
    @{ Name = "$StampPrefix-mix";   Clients = 128; Dur = $MixDur;  Mix = "CAPACITY";   Floor = $null; Living = 9013.292 }
  )

    $gi = 0
  foreach ($g in $stamps) {
    if ($gi -gt 0 -and $RestartBetweenGates) {
      Write-Host "restart HA between gates (fresh heap/WS)..."
      Stop-HaJvms
      Start-Sleep -Seconds ([Math]::Max(15, [Math]::Min(45, $CoolSec / 2)))
      Start-Ha
    }
    Run-Gate $g.Name $g.Clients $g.Dur $g.Mix | Out-Null
    $gi++
  }

  $sb = New-Object System.Text.StringBuilder
  [void]$sb.AppendLine((Get-Content $NotesPath -Raw).TrimEnd())
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("| stamp | clients | dur | TPS | err | p95_ms | p99_ms | vs living | vs floor | gate JSON |")
  [void]$sb.AppendLine("|-------|--------:|----:|----:|----:|-------:|-------:|-----------|----------|-----------|")

  $allPassFloor = $true
  $decisionLines = New-Object System.Collections.Generic.List[string]
  foreach ($g in $stamps) {
    $j = Read-StampJson $g.Name
    if ($null -eq $j) {
      [void]$sb.AppendLine("| ``$($g.Name)`` | $($g.Clients) | $($g.Dur) | n/a | n/a | n/a | n/a | missing | missing | missing |")
      $allPassFloor = $false
      continue
    }
    $tps = [double]$j.tps
    $err = [double]$j.errorRate
    $p95ms = [math]::Round(([double]$j.p95Us) / 1000.0, 2)
    $p99ms = [math]::Round(([double]$j.p99Us) / 1000.0, 2)
    $vsLiv = if ($g.Living -gt 0) { "{0:P1}" -f ($tps / $g.Living) } else { "n/a" }
    $vsFloor = "n/a"
    if ($null -ne $g.Floor) {
      $ok = ($tps -ge $g.Floor) -and ($err -le 0.0)
      $vsFloor = if ($ok) { "PASS (>=$($g.Floor), err=0)" } else { "FAIL (floor=$($g.Floor))" }
      if (-not $ok) { $allPassFloor = $false }
    } else {
      # QG / mix: sizing refs; err=0 and >= 95% living informal
      $ok = ($err -le 0.0) -and ($tps -ge (0.95 * $g.Living))
      $vsFloor = if ($ok) { "PASS (>=95% living $($g.Living))" } else { "BELOW living $($g.Living) or err>0" }
      if (-not $ok) { $allPassFloor = $false }
    }
    [void]$sb.AppendLine("| ``$($g.Name)`` | $($g.Clients) | $($g.Dur) | $tps | $err | $p95ms | $p99ms | $vsLiv of $($g.Living) | $vsFloor | ``$($g.Name)-load-slo.json`` |")
  }

  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## Production decision (draft)")
  [void]$sb.AppendLine("")
  if ($allPassFloor) {
    [void]$sb.AppendLine("- **Candidate to flip** product ``apply-auto-cutover`` default to ``true`` after operator boxes 4-6 and Multi-DC soak.")
    [void]$sb.AppendLine("- Living WRITE/READ floors cleared with cutover=true on this calm stamp.")
  } else {
    [void]$sb.AppendLine("- **Keep product default ``false``.** Clean cutover=true stamp did not clear living floors / sizing refs.")
    [void]$sb.AppendLine("- Do not raise or weaken floors. Next: profile migrate/sealed ship under load, or accept host ceiling with default off.")
  }
  [void]$sb.AppendLine("- Product YAML / ``GridConfigurationProperties.applyAutoCutover`` remains **false** until explicit flip.")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## Operator boxes 4-6")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("| Box | Status |")
  [void]$sb.AppendLine("|-----|--------|")
  [void]$sb.AppendLine("| 4 restart PIN | Still required before flip |")
  [void]$sb.AppendLine("| 5 voter sticky | Still required before flip |")
  [void]$sb.AppendLine("| 6 WAN/restart cutover | Still required before flip |")
  Write-Utf8 $NotesPath $sb.ToString()
  Write-Host "Wrote $NotesPath allPassFloor=$allPassFloor"
  if ($allPassFloor) { exit 0 } else { exit 1 }
} finally {
  Stop-HaJvms
}