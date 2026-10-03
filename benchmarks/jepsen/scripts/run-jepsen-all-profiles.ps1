param(
  [int]$TimeLimit = 45,
  [switch]$SkipRebuild,
  [switch]$Fast,
  [switch]$SkipMultidc,
  [switch]$SkipWitness
)
# Sequential calm Jepsen matrix: existing A-I cells + new swarm + join.
# Writes per-profile stamps to RESULTS.md and a session summary under lab/.
$ErrorActionPreference = "Continue"
$JEPSEN_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ROOT = (Resolve-Path (Join-Path $JEPSEN_DIR "../..")).Path
$Lab = Join-Path $ROOT "grid-server-core\benchmarks\lab"
$StampPrefix = (Get-Date -Format "yyyy-MM-dd") + "-jepsen-edge-matrix"
$SummaryPath = Join-Path $Lab ($StampPrefix + "-summary.md")
$Utf8 = [Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Force -Path $Lab | Out-Null
$env:JEPSEN_TIME_LIMIT = "$TimeLimit"
$rows = New-Object System.Collections.Generic.List[object]

function Find-GitBash {
  foreach ($c in @(
    "C:\Program Files\Git\bin\bash.exe",
    "C:\Program Files\Git\usr\bin\bash.exe",
    "C:\Program Files (x86)\Git\bin\bash.exe"
  )) {
    if (Test-Path $c) { return $c }
  }
  $cmd = Get-Command bash -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  return $null
}

function Clear-JepsenProfileEnv {
  # Prevent join/swarm/unclean flags from bleeding across sequential profiles.
  foreach ($name in @(
      "JEPSEN_JOIN_SHARDS",
      "JEPSEN_SWARM",
      "JEPSEN_UNCLEAN_REVIVE",
      "JEPSEN_UNCLEAN_DOWN_SEC",
      "JEPSEN_MULTIDC",
      "MULTIDC_WORKLOADS",
      "MULTIDC_NEMESIS",
      "MULTIDC_FULL",
      "MULTIDC_SKIP_REBUILD",
      "MULTIDC_MODE"
    )) {
    Remove-Item "Env:$name" -ErrorAction SilentlyContinue
  }
}

function Run-Profile([string]$Name, [string]$Script) {
  Write-Host "`n======== PROFILE $Name ========`n"
  Clear-JepsenProfileEnv
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $doSkip = [bool]($script:SkipRebuild -or $script:Fast)
  $splat = @{
    TimeLimit = $script:TimeLimit
  }
  # Only pass switches the target script declares (avoids binding errors).
  $cmd = Get-Command (Join-Path $PSScriptRoot $Script) -ErrorAction Stop
  if ($doSkip -and $cmd.Parameters.ContainsKey("SkipRebuild")) { $splat["SkipRebuild"] = $true }
  if ($script:Fast -and $cmd.Parameters.ContainsKey("Fast")) { $splat["Fast"] = $true }
  $env:STAMP = "$StampPrefix-$Name"
  # Child scripts may emit pipeline objects; never return them into -ne comparisons
  # (PowerShell array -ne is truthy on any non-matching element). Record FAIL here.
  & (Join-Path $PSScriptRoot $Script) @splat
  $code = if ($null -eq $LASTEXITCODE) { 1 } else { [int]$LASTEXITCODE }
  [void]$sw.Stop()
  Clear-JepsenProfileEnv
  $outcome = if ($code -eq 0) { "PASS" } else { "FAIL" }
  [void]$rows.Add([pscustomobject]@{ profile = $Name; outcome = $outcome; exitCode = $code; seconds = [int]$sw.Elapsed.TotalSeconds })
  if ($code -ne 0) {
    [void]$script:failures.Add($Name)
  }
}

$failures = New-Object System.Collections.Generic.List[string]

Run-Profile "1dc-chaos" "run-jepsen.ps1"
Run-Profile "1dc-nochao" "run-jepsen-nochao.ps1"
Run-Profile "1dc-unclean-revive" "run-jepsen-unclean-revive.ps1"
Run-Profile "1dc-swarm-chaos" "run-jepsen-swarm.ps1"
Run-Profile "1dc-join-shards" "run-jepsen-join.ps1"

if (-not $SkipMultidc) {
  $mdc = Join-Path $JEPSEN_DIR "multidc\scripts"
  # D/E chaos + F/G nochao + L/M swarm/join (COVERAGE Multi-DC)
  foreach ($pair in @(
    @{n="multidc-async-chaos"; s="run-multidc-async.ps1"; noNem=$false},
    @{n="multidc-sync-chaos"; s="run-multidc-sync-voters.ps1"; noNem=$false},
    @{n="multidc-async-nochao"; s="run-multidc-async.ps1"; noNem=$true},
    @{n="multidc-sync-nochao"; s="run-multidc-sync-voters.ps1"; noNem=$true},
    @{n="multidc-async-swarm"; s="run-multidc-swarm.ps1"; noNem=$false},
    @{n="multidc-async-join"; s="run-multidc-join.ps1"; noNem=$false}
  )) {
    $p = Join-Path $mdc $pair.s
    if (Test-Path $p) {
      Write-Host "`n======== PROFILE $($pair.n) ========`n"
      Clear-JepsenProfileEnv
      $sw = [Diagnostics.Stopwatch]::StartNew()
      $env:STAMP = "$StampPrefix-$($pair.n)"
      $splat = @{
        Full = $true
        TimeLimit = $script:TimeLimit
      }
      if ($pair.noNem) { $splat["NoNemesis"] = $true }
      if ($script:SkipRebuild -or $script:Fast) { $splat["SkipRebuild"] = $true }
      & $p @splat
      $code = $LASTEXITCODE
      [void]$sw.Stop()
      Clear-JepsenProfileEnv
      $outcome = if ($code -eq 0) { "PASS" } else { "FAIL" }
      [void]$rows.Add([pscustomobject]@{ profile = $pair.n; outcome = $outcome; exitCode = $code; seconds = [int]$sw.Elapsed.TotalSeconds })
      if ($code -ne 0) { [void]$failures.Add($pair.n) }
    }
  }
  $uncleanSh = Join-Path $mdc "run-multidc-unclean-revive.sh"
  if (Test-Path $uncleanSh) {
    Write-Host "`n======== PROFILE multidc-unclean-revive ========`n"
    Clear-JepsenProfileEnv
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $env:STAMP = "$StampPrefix-multidc-unclean-revive"
    $env:MULTIDC_FULL = "1"
    $env:JEPSEN_TIME_LIMIT = "$script:TimeLimit"
    if ($script:SkipRebuild -or $script:Fast) { $env:MULTIDC_SKIP_REBUILD = "1" }
    $gitBash = Find-GitBash
    if ($null -eq $gitBash) {
      Write-Host "SKIP multidc-unclean-revive: Git bash not found"
      $code = 2
    } else {
      Push-Location $mdc
      try {
        & $gitBash "./run-multidc-unclean-revive.sh"
        $code = $LASTEXITCODE
      } finally {
        Pop-Location
      }
    }
    [void]$sw.Stop()
    $outcome = if ($code -eq 0) { "PASS" } else { "FAIL" }
    [void]$rows.Add([pscustomobject]@{ profile = "multidc-unclean-revive"; outcome = $outcome; exitCode = $code; seconds = [int]$sw.Elapsed.TotalSeconds })
    if ($code -ne 0) { [void]$failures.Add("multidc-unclean-revive") }
  }
}

if (-not $SkipWitness) {
  $w = Join-Path $JEPSEN_DIR "witness\scripts\run-witness-chaos.ps1"
  if (Test-Path $w) {
    Write-Host "`n======== PROFILE witness-chaos ========`n"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $env:STAMP = "$StampPrefix-witness-chaos"
    $splat = @{ TimeLimit = $script:TimeLimit }
    if ($script:SkipRebuild -or $script:Fast) { $splat["SkipRebuild"] = $true }
    & $w @splat
    $code = $LASTEXITCODE
    [void]$sw.Stop()
    $outcome = if ($code -eq 0) { "PASS" } else { "FAIL" }
    [void]$rows.Add([pscustomobject]@{ profile = "witness-chaos"; outcome = $outcome; exitCode = $code; seconds = [int]$sw.Elapsed.TotalSeconds })
    if ($code -ne 0) { [void]$failures.Add("witness-chaos") }
  }
}

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# Jepsen edge matrix summary ($StampPrefix)")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("TimeLimit=$TimeLimit. Failures are recorded for triage - no product fix in this pass.")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("| Profile | Outcome | Exit | Seconds |")
[void]$sb.AppendLine("| --- | --- | --- | --- |")
foreach ($r in $rows) {
  [void]$sb.AppendLine("| $($r.profile) | $($r.outcome) | $($r.exitCode) | $($r.seconds) |")
}
[void]$sb.AppendLine("")
if ($failures.Count -eq 0) {
  [void]$sb.AppendLine("**All listed profiles PASS.**")
} else {
  [void]$sb.AppendLine("## Unexpected FAIL / anomaly (triage later)")
  [void]$sb.AppendLine("")
  foreach ($f in $failures) {
    $resultsHint = "benchmarks/jepsen/RESULTS.md"
    if ($f -like "multidc-*") { $resultsHint = "benchmarks/jepsen/multidc/RESULTS.md" }
    elseif ($f -like "witness-*") { $resultsHint = "benchmarks/jepsen/witness/RESULTS.md" }
    [void]$sb.AppendLine("- **$f** - see ``$resultsHint`` stamp ``$StampPrefix-$f`` and Clojure ``store/`` history for Elle/Knossos detail.")
  }
}
[IO.File]::WriteAllText($SummaryPath, $sb.ToString(), $Utf8)
Write-Host "summary -> $SummaryPath"
$rows | Format-Table -AutoSize
if ($failures.Count -gt 0) { exit 1 }
exit 0
