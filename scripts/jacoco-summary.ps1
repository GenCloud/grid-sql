# Summarize JaCoCo instruction + branch coverage from aggregate CSV.
# Usage (after mvn -pl grid-coverage -am verify):
#   powershell -File .\scripts\jacoco-summary.ps1
param(
    [string]$CsvPath = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $CsvPath) {
    $CsvPath = Join-Path $root "grid-coverage\target\site\jacoco-aggregate\jacoco.csv"
}
if (-not (Test-Path -LiteralPath $CsvPath)) {
    throw "JaCoCo CSV not found: $CsvPath`nRun: mvn -pl grid-coverage -am verify"
}

$instrMissed = 0L
$instrCovered = 0L
$branchMissed = 0L
$branchCovered = 0L
$byGroup = @{}

# CSV: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,...
Get-Content -LiteralPath $CsvPath | Select-Object -Skip 1 | ForEach-Object {
    if ([string]::IsNullOrWhiteSpace($_)) { return }
    $cols = $_ -split ","
    if ($cols.Length -lt 7) { return }
    $g = $cols[0]
    $im = [long]$cols[3]
    $ic = [long]$cols[4]
    $bm = [long]$cols[5]
    $bc = [long]$cols[6]
    $instrMissed += $im
    $instrCovered += $ic
    $branchMissed += $bm
    $branchCovered += $bc
    if (-not $byGroup.ContainsKey($g)) {
        $byGroup[$g] = [pscustomobject]@{ Group = $g; InstrMissed = 0L; InstrCovered = 0L; BranchMissed = 0L; BranchCovered = 0L }
    }
    $byGroup[$g].InstrMissed += $im
    $byGroup[$g].InstrCovered += $ic
    $byGroup[$g].BranchMissed += $bm
    $byGroup[$g].BranchCovered += $bc
}

function Pct([long]$covered, [long]$missed) {
    $total = $covered + $missed
    if ($total -eq 0) { return 100.0 }
    return [math]::Round(100.0 * $covered / $total, 2)
}

function ShieldColor([double]$pct) {
    if ($pct -ge 80.0) { return "brightgreen" }
    if ($pct -ge 60.0) { return "yellowgreen" }
    if ($pct -ge 40.0) { return "yellow" }
    return "orange"
}

Write-Host "JaCoCo aggregate: $CsvPath"
Write-Host ""
Write-Host ("{0,-28} {1,10} {2,10}" -f "Module", "Instruction%", "Branch%")
Write-Host ("-" * 52)
foreach ($g in ($byGroup.Keys | Sort-Object)) {
    $row = $byGroup[$g]
    Write-Host ("{0,-28} {1,10:N2} {2,10:N2}" -f $g, (Pct $row.InstrCovered $row.InstrMissed), (Pct $row.BranchCovered $row.BranchMissed))
}
Write-Host ("-" * 52)
$iPct = Pct $instrCovered $instrMissed
$bPct = Pct $branchCovered $branchMissed
Write-Host ("{0,-28} {1,10:N2} {2,10:N2}" -f "TOTAL", $iPct, $bPct)
Write-Host ""
Write-Host ("Instructions: covered={0} missed={1} total={2}" -f $instrCovered, $instrMissed, ($instrCovered + $instrMissed))
Write-Host ("Branches:     covered={0} missed={1} total={2}" -f $branchCovered, $branchMissed, ($branchCovered + $branchMissed))
Write-Host ("HTML: grid-coverage/target/site/jacoco-aggregate/index.html")

$badgeDir = Join-Path $root "docs\assets\badges"
if (-not (Test-Path -LiteralPath $badgeDir)) {
    New-Item -ItemType Directory -Path $badgeDir | Out-Null
}
$msg = ("{0:0.##}%" -f $iPct).Replace(",", ".")
$color = ShieldColor $iPct
$json = @"
{
  "schemaVersion": 1,
  "label": "coverage",
  "message": "$msg",
  "color": "$color"
}
"@
$badgePath = Join-Path $badgeDir "jacoco-shields.json"
[IO.File]::WriteAllText($badgePath, $json.Trim() + "`n", [Text.UTF8Encoding]::new($false))
Write-Host ("Badge:  docs/assets/badges/jacoco-shields.json ({0})" -f $msg)
