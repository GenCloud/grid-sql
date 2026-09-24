# Build sampler + stage runtime jars for JMeter GUI / -Juser.classpath.
# SessionRole lives in grid-commons - must be on CP (not only grid-sql-client).
param(
  [string]$JMeterHome = $(if ($env:JMETER_HOME) { $env:JMETER_HOME } else { "d:/apache-jmeter-5.6.3" })
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

Write-Host "=== mvn package grid-sql-jmeter -am ==="
mvn -pl grid-sql-jmeter -am package -DskipTests

if ($LASTEXITCODE -ne 0) { throw "mvn failed: $LASTEXITCODE" }

$Jar = Join-Path $Root "grid-sql-jmeter\target\grid-sql-jmeter-1.0-SNAPSHOT.jar"
$DepDir = Join-Path $Root "grid-sql-jmeter\target\dependency"
$Exclude = @("log4j-to-slf4j", "log4j-slf4j-impl", "slf4j-reload4j", "slf4j-log4j12")
$parts = New-Object System.Collections.Generic.List[string]
$parts.Add((Resolve-Path $Jar).Path)
Get-ChildItem $DepDir -Filter "*.jar" | Where-Object {
  $base = $_.BaseName
  -not ($Exclude | Where-Object { $base.StartsWith($_) })
} | ForEach-Object { $parts.Add($_.FullName) }

if (-not ($parts | Where-Object { $_ -match 'grid-commons' })) {
  throw "grid-commons missing from target/dependency - SessionRole will CNF"
}

$cp = ($parts -join ";")
$out = Join-Path $Root "grid-sql-jmeter\target\jmeter-user.classpath.txt"
[IO.File]::WriteAllText($out, $cp + "`n", [Text.UTF8Encoding]::new($false))
Write-Host "OK entries=$($parts.Count)"
Write-Host "Classpath file: $out"
Write-Host "GUI: paste that single line into user.classpath, then restart JMeter."
Write-Host "CLI: powershell -File .\scripts\run-jmeter-load-slo.ps1 -Profile capacity -Clients 64"
Write-Host "Need: grid-sql-jmeter + grid-sql-client + grid-commons + target\dependency jars"
