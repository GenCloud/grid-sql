# Shared Jepsen host bootstrap - environment-agnostic (Windows GHA, self-hosted, local).
# Dot-source:  . (Join-Path $PSScriptRoot "jepsen-env.ps1"); Initialize-JepsenHostEnv
# Never hardcode /home/runner, drive letters, or Unix-only mvn wrappers.

function Get-JepsenRepoRoot {
  param([string]$FromScriptRoot = $PSScriptRoot)
  $dir = (Resolve-Path $FromScriptRoot).Path
  while ($dir) {
    $pom = Join-Path $dir "pom.xml"
    $jepsen = Join-Path $dir "benchmarks"
    $jepsen = Join-Path $jepsen "jepsen"
    if ((Test-Path -LiteralPath $pom) -and (Test-Path -LiteralPath $jepsen)) {
      return $dir
    }
    $parent = Split-Path $dir -Parent
    if (-not $parent -or $parent -eq $dir) {
      break
    }
    $dir = $parent
  }
  throw "Cannot locate repo root (pom.xml + benchmarks/jepsen) from $FromScriptRoot"
}

function Get-JepsenPublicHostLabel {
  if ($env:GITHUB_ACTIONS -eq "true") { return "gha" }
  return "local"
}

function Get-JepsenSafeLogPath {
  param([string]$PathValue)
  if (-not $PathValue) { return "" }
  if ($env:GITHUB_ACTIONS -ne "true") { return $PathValue }
  $ws = [string]$env:GITHUB_WORKSPACE
  if ($ws -and $PathValue.StartsWith($ws, [StringComparison]::OrdinalIgnoreCase)) {
    return $PathValue.Substring($ws.Length).TrimStart('\', '/')
  }
  $up = [string]$env:USERPROFILE
  if ($up -and $PathValue.StartsWith($up, [StringComparison]::OrdinalIgnoreCase)) {
    return "~" + $PathValue.Substring($up.Length)
  }
  return "(host-path)"
}

function Get-JepsenDir {
  param([string]$FromScriptRoot = $PSScriptRoot)
  $root = Get-JepsenRepoRoot -FromScriptRoot $FromScriptRoot
  return (Join-Path $root (Join-Path "benchmarks" "jepsen"))
}

function Find-GitBash {
  $candidates = New-Object System.Collections.Generic.List[string]
  if ($env:ProgramFiles) {
    [void]$candidates.Add((Join-Path $env:ProgramFiles "Git\bin\bash.exe"))
    [void]$candidates.Add((Join-Path $env:ProgramFiles "Git\usr\bin\bash.exe"))
  }
  $pf86 = ${env:ProgramFiles(x86)}
  if ($pf86) {
    [void]$candidates.Add((Join-Path $pf86 "Git\bin\bash.exe"))
  }
  if ($env:LOCALAPPDATA) {
    [void]$candidates.Add((Join-Path $env:LOCALAPPDATA "Programs\Git\bin\bash.exe"))
  }
  foreach ($c in $candidates) {
    if ($c -and (Test-Path -LiteralPath $c)) { return $c }
  }
  $cmd = Get-Command bash -ErrorAction SilentlyContinue
  if ($cmd -and $cmd.Source) { return $cmd.Source }
  return $null
}

function Find-MavenCmd {
  $cmdCmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
  if ($cmdCmd) { return $cmdCmd.Source }
  $cmd = Get-Command mvn -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  return $null
}

function Get-JepsenUserHome {
  if ($env:USERPROFILE -and $env:USERPROFILE.Trim().Length -gt 0) { return $env:USERPROFILE }
  if ($env:HOME -and $env:HOME.Trim().Length -gt 0) { return $env:HOME }
  try {
    $folder = [Environment]::GetFolderPath("UserProfile")
    if ($folder) { return $folder }
  } catch { }
  return (Get-Location).Path
}

function Get-GridSqlProjectVersion {
  param([string]$RepoRoot = "")
  if (-not $RepoRoot) {
    $RepoRoot = Get-JepsenRepoRoot
  }
  $pom = Join-Path $RepoRoot "pom.xml"
  if (-not (Test-Path -LiteralPath $pom)) {
    throw "root pom.xml missing: $pom"
  }
  $text = [IO.File]::ReadAllText($pom)
  # Project version is the first <version> after <artifactId>grid-sql</artifactId>
  # (Spring Boot parent version lives later inside <parent>).
  $m = [regex]::Match(
    $text,
    '<artifactId>\s*grid-sql\s*</artifactId>\s*<version>\s*([^<]+?)\s*</version>',
    [Text.RegularExpressions.RegexOptions]::Singleline)
  if (-not $m.Success) {
    throw "could not parse grid-sql project version from $pom"
  }
  return $m.Groups[1].Value.Trim()
}

function Sync-JepsenProjectClj {
  param([string]$RepoRoot = "")
  if (-not $RepoRoot) {
    $RepoRoot = Get-JepsenRepoRoot
  }
  $ver = Get-GridSqlProjectVersion -RepoRoot $RepoRoot
  $clj = Join-Path $RepoRoot (Join-Path "benchmarks" (Join-Path "jepsen" (Join-Path "clojure" "project.clj")))
  if (-not (Test-Path -LiteralPath $clj)) {
    throw "missing Jepsen project.clj: $clj"
  }
  $text = [IO.File]::ReadAllText($clj)
  $pattern = 'org\.genfork/grid-sql-client "[^"]*"'
  $replacement = "org.genfork/grid-sql-client `"$ver`""
  $updated = [regex]::Replace($text, $pattern, $replacement)
  if ($updated -notmatch [regex]::Escape("org.genfork/grid-sql-client `"$ver`"")) {
    throw "failed to write grid-sql-client `"$ver`" into $clj"
  }
  if ($updated -ne $text) {
    [IO.File]::WriteAllText($clj, $updated, [Text.UTF8Encoding]::new($false))
  }
  Write-Host "Synced Jepsen project.clj → org.genfork/grid-sql-client `"$ver`" (from root pom.xml)"
  return $ver
}

function Test-JepsenSqlClientInstalled {
  param(
    [string]$Version = "",
    [string]$M2Home = ""
  )
  if (-not $Version) {
    $Version = Get-GridSqlProjectVersion
  }
  if (-not $M2Home) {
    $M2Home = $env:JEPSEN_M2
  }
  if (-not $M2Home) {
    $M2Home = Join-Path (Get-JepsenUserHome) ".m2"
  }
  $jar = Join-Path $M2Home (
    Join-Path "repository" (
      Join-Path "org" (
        Join-Path "genfork" (
          Join-Path "grid-sql-client" (
            Join-Path $Version ("grid-sql-client-" + $Version + ".jar"))))))
  return (Test-Path -LiteralPath $jar)
}

function Initialize-JepsenHostEnv {
  $homeDir = Get-JepsenUserHome
  if (-not $env:HOME -or $env:HOME.Trim().Length -eq 0) {
    $env:HOME = $homeDir
  }
  if (-not $env:USERPROFILE -or $env:USERPROFILE.Trim().Length -eq 0) {
    $env:USERPROFILE = $homeDir
  }
  if (-not $env:JEPSEN_M2 -or $env:JEPSEN_M2.Trim().Length -eq 0) {
    $env:JEPSEN_M2 = Join-Path $homeDir ".m2"
  }
  $env:MSYS_NO_PATHCONV = "1"
  $env:DOCKER_BUILDKIT = "1"
  if (-not $env:MSYS2_ARG_CONV_EXCL) {
    $env:MSYS2_ARG_CONV_EXCL = "*"
  }
  # Keep Leiningen coord aligned with Maven project.version (no hardcoded client version).
  Sync-JepsenProjectClj | Out-Null
}

function Invoke-JepsenBash {
  param(
    [Parameter(Mandatory = $true)][string]$ScriptPath,
    [string[]]$Arguments = @()
  )
  $bash = Find-GitBash
  if (-not $bash) {
    throw "Git Bash not found (needed for $ScriptPath). Install Git for Windows or put bash on PATH."
  }
  $prev = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    & $bash $ScriptPath @Arguments
    return $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $prev
  }
}

function Clear-JepsenCellEnv {
  foreach ($name in @(
      "JEPSEN_JOIN_SHARDS",
      "JEPSEN_SWARM",
      "JEPSEN_UNCLEAN_REVIVE",
      "JEPSEN_UNCLEAN_DOWN_SEC",
      "JEPSEN_MULTIDC",
      "JEPSEN_WITNESS",
      "JEPSEN_INSTANCE",
      "JEPSEN_PORT_OFFSET",
      "MULTIDC_WORKLOADS",
      "MULTIDC_NEMESIS",
      "MULTIDC_FULL",
      "MULTIDC_SKIP_REBUILD",
      "MULTIDC_MODE",
      "COMPOSE_PROJECT_NAME"
    )) {
    Remove-Item "Env:$name" -ErrorAction SilentlyContinue
  }
}