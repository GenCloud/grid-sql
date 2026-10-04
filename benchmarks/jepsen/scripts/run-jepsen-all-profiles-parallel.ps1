param(
  [int]$TimeLimit = 60,
  [switch]$SkipRebuild,
  [int]$PortStride = 100,
  [int]$MaxParallel = 2,
  [string]$Profiles = "all"
)
$ErrorActionPreference = "Continue"
$JEPSEN_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ROOT = (Resolve-Path (Join-Path $JEPSEN_DIR "../..")).Path
$RootUnix = ($ROOT -replace '\\','/' -replace '^([A-Za-z]):', { '/{0}' -f $args[0].Groups[1].Value.ToLower() })
if ($RootUnix -match '^[A-Za-z]:') { $RootUnix = '/' + $RootUnix.Substring(0,1).ToLower() + $RootUnix.Substring(2).Replace('\','/') }
$RootUnix = ($ROOT -replace '\\','/')
if ($RootUnix -match '^([A-Za-z]):(.*)$') { $RootUnix = '/' + $Matches[1].ToLower() + $Matches[2] }
$Lab = Join-Path $ROOT "grid-server-core\benchmarks\lab"
$StampPrefix = (Get-Date -Format "yyyy-MM-dd-HHmm") + "-jepsen-parallel"
$SummaryPath = Join-Path $Lab ($StampPrefix + "-summary.md")
$Utf8 = [Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Force -Path $Lab | Out-Null
$bash = @("C:\Program Files\Git\bin\bash.exe","C:\Program Files\Git\usr\bin\bash.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $bash) { throw "Git bash not found" }
$jdk = Join-Path $env:USERPROFILE ".jdks\temurin-25"
if (Test-Path (Join-Path $jdk "bin\java.exe")) { $env:JAVA_HOME = $jdk; $env:Path = (Join-Path $jdk "bin") + ";" + $env:Path }
$env:JEPSEN_M2 = Join-Path $env:USERPROFILE ".m2"
$env:MSYS_NO_PATHCONV = "1"

function To-UnixPath([string]$p) {
  $x = $p -replace '\\','/'
  if ($x -match '^([A-Za-z]):(.*)$') { return '/' + $Matches[1].ToLower() + $Matches[2] }
  return $x
}

$all = @(
  @{ id="A"; config="1dc-chaos"; script="benchmarks/jepsen/scripts/run-jepsen.sh"; args=@(); idx=0 },
  @{ id="B"; config="1dc-unclean-revive"; script="benchmarks/jepsen/scripts/run-jepsen-unclean-revive.sh"; args=@(); idx=1 },
  @{ id="C"; config="1dc-nochao"; script="benchmarks/jepsen/scripts/run-jepsen-nochao.sh"; args=@(); idx=2 },
  @{ id="D"; config="multidc-async-chaos"; script="benchmarks/jepsen/multidc/scripts/run-multidc-chaos.sh"; args=@("async"); idx=3 },
  @{ id="E"; config="multidc-sync-chaos"; script="benchmarks/jepsen/multidc/scripts/run-multidc-chaos.sh"; args=@("sync-voters"); idx=4 },
  @{ id="F"; config="multidc-async-nochao"; script="benchmarks/jepsen/multidc/scripts/run-multidc-nochao.sh"; args=@("async"); idx=5 },
  @{ id="G"; config="multidc-sync-nochao"; script="benchmarks/jepsen/multidc/scripts/run-multidc-nochao.sh"; args=@("sync-voters"); idx=6 },
  @{ id="I"; config="multidc-unclean-revive"; script="benchmarks/jepsen/multidc/scripts/run-multidc-unclean-revive.sh"; args=@(); idx=7 },
  @{ id="J"; config="1dc-swarm-chaos"; script="benchmarks/jepsen/scripts/run-jepsen-swarm.sh"; args=@(); idx=8 },
  @{ id="K"; config="1dc-join-shards"; script="benchmarks/jepsen/scripts/run-jepsen-join.sh"; args=@(); idx=9 },
  @{ id="L"; config="multidc-async-swarm"; script="benchmarks/jepsen/multidc/scripts/run-multidc-swarm.sh"; args=@(); idx=10 },
  @{ id="M"; config="multidc-async-join"; script="benchmarks/jepsen/multidc/scripts/run-multidc-join.sh"; args=@(); idx=11 }
)
if ($Profiles -ne "all") {
  $want = $Profiles.Split(",") | ForEach-Object { $_.Trim().ToUpperInvariant() } | Where-Object { $_ }
  $all = @($all | Where-Object { $want -contains $_.id })
}
if ($all.Count -eq 0) { throw "No profiles matched: $Profiles" }

if (-not $SkipRebuild) {
  Write-Host "=== shared image build ==="
  & $bash (Join-Path $JEPSEN_DIR "scripts\build-jepsen-image.sh")
  if ($LASTEXITCODE -ne 0) { throw "build-jepsen-image failed" }
}
# One shared install for all cells — never concurrent mvn on target/ (Windows jar races).
Write-Host "=== shared mvn install (grid-sql-client) ==="
Push-Location $ROOT
try {
  mvn -B -pl grid-sql-jepsen-starter,grid-sql-client -am install "-Dmaven.test.skip=true" -q
  if ($LASTEXITCODE -ne 0) { throw "shared mvn install failed" }
} finally { Pop-Location }

$jobs = @()
foreach ($p in $all) {
  while ((@($jobs | Where-Object { $_.State -eq 'Running' })).Count -ge $MaxParallel) { Start-Sleep -Seconds 5 }
  $offset = $p.idx * $PortStride
  $stamp = "$StampPrefix-$($p.config)"
  $log = Join-Path $Lab "$stamp.log"
  $resultsFile = Join-Path $Lab "$stamp-RESULTS.md"
  $scriptUnix = To-UnixPath (Join-Path $ROOT ($p.script -replace '/','\'))
  $rootUnix = To-UnixPath $ROOT
  $resultsUnix = To-UnixPath $resultsFile
  $argStr = ($p.args -join ' ')
  Write-Host "START $($p.id) $($p.config) offset=$offset"
  $jobs += Start-Job -Name "jepsen-$($p.id)" -ScriptBlock {
    param($Bash,$RootWin,$RootUnix,$ScriptUnix,$ArgStr,$Inst,$Offset,$Stamp,$Tl,$ResultsUnix,$LogWin,$JavaHome,$M2)
    $env:JAVA_HOME=$JavaHome; $env:Path="$JavaHome\bin;" + $env:Path
    $env:JEPSEN_M2=$M2; $env:JEPSEN_INSTANCE=$Inst; $env:JEPSEN_PORT_OFFSET="$Offset"
    $env:JEPSEN_TIME_LIMIT="$Tl"; $env:STAMP=$Stamp; $env:JEPSEN_RESULTS_FILE=$ResultsUnix
    $env:MULTIDC_FULL="1"; $env:MULTIDC_SKIP_REBUILD="1"; $env:JEPSEN_REBUILD="0"
    $env:JEPSEN_SKIP_MVN_INSTALL="1"; $env:MSYS_NO_PATHCONV="1"
    Set-Location $RootWin
    $inner = "set -e; cd '$RootUnix'; source benchmarks/jepsen/scripts/jepsen-instance-env.sh; bash '$ScriptUnix' $ArgStr; ec=`$?; echo EXIT:`$ec; exit `$ec"
    & $Bash -lc $inner *> $LogWin
    $code = $LASTEXITCODE
    if (Select-String -Path $LogWin -Pattern ':valid\? false|OUTCOME=FAIL|outcome: FAIL|LEIN_EXIT=[1-9]' -Quiet) { $code = 1 }
    return [pscustomobject]@{ id=$Inst; exit=$code; log=$LogWin; stamp=$Stamp }
  } -ArgumentList $bash,$ROOT,$rootUnix,$scriptUnix,$argStr,$p.id,$offset,$stamp,$TimeLimit,$resultsUnix,$log,$env:JAVA_HOME,$env:JEPSEN_M2
}
Write-Host "Waiting $($jobs.Count) jobs..."
$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job -Force
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]
foreach ($r in $results) {
  $outcome = if ($r.exit -eq 0) { "PASS" } else { "FAIL" }
  [void]$rows.Add([pscustomobject]@{ profile=$r.id; outcome=$outcome; exitCode=$r.exit; stamp=$r.stamp; log=$r.log })
  if ($r.exit -ne 0) { [void]$failures.Add($r.id) }
}
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# Jepsen parallel matrix ($StampPrefix)")
[void]$sb.AppendLine(""); [void]$sb.AppendLine("TimeLimit=$TimeLimit PortStride=$PortStride MaxParallel=$MaxParallel")
[void]$sb.AppendLine(""); [void]$sb.AppendLine("| Profile | Outcome | Exit | Stamp | Log |"); [void]$sb.AppendLine("| --- | --- | --- | --- | --- |")
foreach ($r in $rows) { [void]$sb.AppendLine("| $($r.profile) | $($r.outcome) | $($r.exitCode) | ``$($r.stamp)`` | ``$($r.log)`` |") }
[void]$sb.AppendLine("")
if ($failures.Count -eq 0) { [void]$sb.AppendLine("**All listed profiles PASS.**") } else { [void]$sb.AppendLine("## FAIL"); foreach ($f in $failures) { [void]$sb.AppendLine("- **$f**") } }
[IO.File]::WriteAllText($SummaryPath, $sb.ToString(), $Utf8)
Write-Host "summary -> $SummaryPath"
$rows | Format-Table -AutoSize
if ($failures.Count -gt 0) { exit 1 }
exit 0