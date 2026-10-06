# Mask host identity and secret-like env values in GitHub Actions logs.
# No-op on GitHub-hosted runners.
$ErrorActionPreference = "Continue"
$re = [string]$env:RUNNER_ENVIRONMENT
$rl = [string]$env:RUNNER_LABELS
$self = ($re -eq "self-hosted") -or (",$rl,".ToLowerInvariant().Contains(",self-hosted,"))
if (-not $self) { exit 0 }

function Invoke-GhaMask([string]$Value) {
  if (-not $Value) { return }
  $v = $Value.Trim()
  if ($v.Length -lt 4) { return }
  switch -Regex ($v) {
    '^(true|false|Linux|Windows|macOS|ubuntu-latest|self-hosted)$' { return }
  }
  Write-Output "::add-mask::$v"
}

function Invoke-GhaMaskPath([string]$PathValue) {
  if (-not $PathValue) { return }
  Invoke-GhaMask $PathValue
  Invoke-GhaMask ($PathValue -replace '\\', '/')
  Invoke-GhaMask ($PathValue -replace '/', '\')
}

Invoke-GhaMask $env:USER
Invoke-GhaMask $env:USERNAME
Invoke-GhaMask $env:LOGNAME
Invoke-GhaMask $env:COMPUTERNAME
Invoke-GhaMask $env:HOSTNAME
Invoke-GhaMask $env:RUNNER_NAME
try {
  Invoke-GhaMask ([System.Net.Dns]::GetHostName())
} catch { }

foreach ($k in @(
    "HOME", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "TMP", "TEMP",
    "GITHUB_WORKSPACE", "RUNNER_TEMP", "RUNNER_TOOL_CACHE", "RUNNER_WORKSPACE",
    "JAVA_HOME", "JAVA_HOME_25_X64", "M2_HOME", "MAVEN_HOME", "JEPSEN_M2"
  )) {
  Invoke-GhaMaskPath ([Environment]::GetEnvironmentVariable($k))
}

if ($env:GITHUB_WORKSPACE) {
  try {
    $parent = (Resolve-Path (Join-Path $env:GITHUB_WORKSPACE "..\..\..")).Path
    Invoke-GhaMaskPath $parent
  } catch { }
}

$skip = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
foreach ($s in @(
    "GITHUB_TOKEN", "ACTIONS_RUNTIME_TOKEN", "ACTIONS_RESULTS_URL",
    "GITHUB_ENV", "GITHUB_OUTPUT", "GITHUB_PATH", "GITHUB_STATE", "GITHUB_STEP_SUMMARY",
    "PATH", "PATHEXT", "COMSPEC", "WINDIR", "SYSTEMROOT", "PROGRAMFILES", "PROGRAMDATA"
  )) { [void]$skip.Add($s) }

Get-ChildItem Env: | ForEach-Object {
  if ($skip.Contains($_.Name)) { return }
  $n = $_.Name.ToUpperInvariant()
  if ($n -match 'SECRET|TOKEN|PASSWORD|PASSWD|APIKEY|API_KEY|CREDENTIAL|PRIVATE_KEY|ACCESS_KEY|CONNECTION_STRING|CONNSTR|AUTH_HEADER') {
    Invoke-GhaMask $_.Value
  }
}

if ($env:GITHUB_ENV) {
  $existing = [string]$env:JAVA_TOOL_OPTIONS
  if ($existing -notmatch '-Duser\.name=gha') {
    Add-Content -Path $env:GITHUB_ENV -Value "JAVA_TOOL_OPTIONS=$existing -Duser.name=gha" -Encoding utf8
  }
}
exit 0
