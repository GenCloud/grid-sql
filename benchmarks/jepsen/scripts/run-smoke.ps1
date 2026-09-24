$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
$Repo = (Resolve-Path (Join-Path $Root "../..")).Path
Set-Location $Root

$ComposeStatus = "validated"
$Chaos = "skipped"
$Full = "not-run"
$Outcome = "HARNESS_READY"
$Notes = "compose config ok; full Clojure Jepsen not run"
$Failed = 0

Write-Host "=== jepsen smoke ==="

$composeOut = docker compose config 2>&1
if ($LASTEXITCODE -ne 0) {
  Write-Host "FAIL: docker compose config"
  Write-Host $composeOut
  $ComposeStatus = "invalid"
  $Outcome = "FAIL"
  $Failed = 1
} else {
  Write-Host "OK: docker compose config"
}

$jdk25 = "C:\Users\MrCloud\.jdks\temurin-25"
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
  $jdk = $env:JAVA_HOME
} elseif (Test-Path (Join-Path $jdk25 "bin\java.exe")) {
  $jdk = $jdk25
} else {
  $jdk = $null
}

if ($jdk) {
  $env:JAVA_HOME = $jdk
  $env:Path = "$jdk\bin;" + $env:Path
  Write-Host "Using JAVA_HOME=$jdk"
  Push-Location $Repo
  try {
    mvn -B -pl grid-server-core "-Dtest=index.unit.replication.chaos.LinearizabilityIT,index.unit.replication.chaos.Partition3NodeIT" test
    if ($LASTEXITCODE -eq 0) {
      $Chaos = "pass"
      $Notes = "compose config ok; chaos ITs pass; full Jepsen not run (lein absent on Windows)"
    } else {
      $Chaos = "fail"
      $Outcome = "FAIL"
      $Notes = "chaos ITs failed"
      $Failed = 1
    }
  } catch {
    $Chaos = "fail"
    $Outcome = "FAIL"
    $Notes = "chaos ITs error: $_"
    $Failed = 1
  } finally {
    Pop-Location
  }
} else {
  Write-Host "Skip chaos ITs (no JDK 25 JAVA_HOME)"
  $Notes = "compose config ok; chaos ITs skipped; lein absent"
}

$env:STAMP = if ($env:STAMP) { $env:STAMP } else { (Get-Date -Format "yyyy-MM-dd") + "-jepsen-smoke" }
$env:MODE = "smoke"
$env:OUTCOME = $Outcome
$env:COMPOSE_STATUS = $ComposeStatus
$env:CHAOS = $Chaos
$env:FULL = $Full
$env:NOTES = $Notes
$env:COMMAND = "run-jepsen-smoke.ps1"
& (Join-Path $PSScriptRoot "stamp-results.ps1")

exit $Failed
