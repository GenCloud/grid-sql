# Wait until exactly one Active voter reports writerEligible=true on /health/readiness.
param(
  [string]$PortsCsv = "7777,7778,7779",
  [string]$SettleHost = "127.0.0.1",
  [int]$DeadlineSec = 180,
  [int]$PollSec = 5,
  [int]$PostReadySleepSec = 8
)
$ErrorActionPreference = "Continue"
$sh = Join-Path $PSScriptRoot "wait-writer-eligible.sh"
$gitBash = @(
  "C:\Program Files\Git\bin\bash.exe",
  "C:\Program Files\Git\usr\bin\bash.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($gitBash) {
  $env:WRITER_SETTLE_DEADLINE_SEC = "$DeadlineSec"
  $env:WRITER_SETTLE_POLL_SEC = "$PollSec"
  $env:POST_READY_SLEEP_SEC = "$PostReadySleepSec"
  $env:WRITER_SETTLE_HOST = $SettleHost
  & $gitBash $sh $PortsCsv
  exit $LASTEXITCODE
}
Write-Host "wait-writer-eligible.ps1: Git bash missing; using Invoke-WebRequest fallback"
$deadline = (Get-Date).AddSeconds($DeadlineSec)
$ports = $PortsCsv.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
do {
  $eligible = 0
  $detail = ""
  foreach ($port in $ports) {
    try {
      $r = Invoke-WebRequest -Uri "http://${SettleHost}:${port}/health/readiness" -TimeoutSec 3 -UseBasicParsing
      $body = $r.Content
      if ($body -match '"writerEligible"\s*:\s*true') {
        $eligible++
        $detail += "${port}=eligible;"
      } elseif ($body -match '"status"\s*:\s*"UP"') {
        $detail += "${port}=up-no-writer;"
      } else {
        $detail += "${port}=not-ready;"
      }
    } catch {
      $detail += "${port}=down;"
    }
  }
  if ($eligible -eq 1) {
    Write-Host "wait-writer-eligible: OK ($detail)"
    Start-Sleep -Seconds $PostReadySleepSec
    exit 0
  }
  Write-Host "wait-writer-eligible: eligible=$eligible ($detail) retry in ${PollSec}s"
  Start-Sleep -Seconds $PollSec
} while ((Get-Date) -lt $deadline)
Write-Host "wait-writer-eligible: TIMEOUT eligible=$eligible ($detail)"
exit 1