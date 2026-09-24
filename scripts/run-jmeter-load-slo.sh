#!/usr/bin/env bash
# Apache JMeter load-SLO gate. Reports = Aggregate/Summary Report CSV + HTML Dashboard (-e -o).
# Calm host only. Product grid:// (never JDBC tooling / jdbc:grid://). No custom Python reporter.
set -euo pipefail

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x "$HOME/.jdks/temurin-25/bin/javac" ]]; then
    export JAVA_HOME="$HOME/.jdks/temurin-25"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RESULTS="$ROOT/grid-server-core/benchmarks/lab"
MODULE="$ROOT/grid-sql-jmeter"
JMX="$MODULE/grid-sql-load.jmx"
WORK="$MODULE/target/jmeter-run"
mkdir -p "$RESULTS" "$WORK"

STAMP="${1:-${JMH_STAMP:-$(date +%Y-%m-%d)-load-slo}}"
CLIENTS="${LOAD_SLO_CLIENTS:-8}"
DURATION="${LOAD_SLO_DURATION_SEC:-30}"
RAMP_SEC="${LOAD_SLO_RAMP_SEC:-5}"
GRID_URL="${LOAD_SLO_GRID_URL:-grid://@127.0.0.1:15432/public}"
USER="${LOAD_SLO_USER:-}"
PASSWORD="${LOAD_SLO_PASSWORD:-}"
TPS_FLOOR="${LOAD_SLO_TPS_FLOOR:-1}"
P95_CEIL="${LOAD_SLO_P95_CEILING_US:-1000000000}"
P99_CEIL="${LOAD_SLO_P99_CEILING_US:-1000000000}"
ERR_CEIL="${LOAD_SLO_ERROR_RATE_CEILING:-0}"
JMETER_HOME="${JMETER_HOME:-}"
DRY_SMOKE="${LOAD_SLO_DRY_SMOKE:-0}"
SKIP_BUILD="${LOAD_SLO_SKIP_BUILD:-0}"
HTML_REPORT="${LOAD_SLO_HTML_REPORT:-1}"

if [[ -z "$JMETER_HOME" ]]; then
  if [[ -d "/opt/apache-jmeter-5.6.3" ]]; then
    JMETER_HOME="/opt/apache-jmeter-5.6.3"
  elif [[ -d "$HOME/apache-jmeter-5.6.3" ]]; then
    JMETER_HOME="$HOME/apache-jmeter-5.6.3"
  else
    JMETER_HOME="d:/apache-jmeter-5.6.3"
  fi
fi

echo "=== run-jmeter-load-slo stamp=$STAMP clients=$CLIENTS duration=${DURATION}s ==="
echo "JMETER_HOME=$JMETER_HOME"
echo "Reports: Aggregate/Summary Report CSV + HTML Dashboard (-e -o)"

if [[ ! -f "$JMX" ]]; then
  echo "Missing JMX: $JMX" >&2
  exit 1
fi
if [[ ! -d "$JMETER_HOME" ]]; then
  echo "WARN: JMETER_HOME not found at $JMETER_HOME" >&2
  if [[ "$DRY_SMOKE" != "1" ]]; then
    exit 1
  fi
fi

JAR="$MODULE/target/grid-sql-jmeter-1.0-SNAPSHOT.jar"
DEP_DIR="$MODULE/target/dependency"
REPORT_DIR="$WORK/${STAMP}-reports"
mkdir -p "$REPORT_DIR"

if [[ "$SKIP_BUILD" != "1" ]]; then
  (cd "$ROOT" && mvn -pl grid-sql-jmeter -am package -DskipTests "-Djmeter.home=$JMETER_HOME")
fi
if [[ ! -f "$JAR" ]]; then
  echo "Sampler jar missing: $JAR" >&2
  exit 1
fi

CP="$JAR"
# JMeter ships log4j-slf4j-impl; Spring log4j-to-slf4j on the same CP breaks sampler class init.
if [[ -d "$DEP_DIR" ]]; then
  while IFS= read -r -d '' j; do
    base=$(basename "$j")
    case "$base" in
      log4j-to-slf4j*|log4j-slf4j-impl*|slf4j-reload4j*|slf4j-log4j12*) continue ;;
    esac
    CP="$CP:$j"
  done < <(find "$DEP_DIR" -name '*.jar' -print0 2>/dev/null || true)
fi
CP_COUNT=$(echo "$CP" | tr ':' '\n' | grep -c . || true)

if [[ "$DRY_SMOKE" == "1" ]]; then
  OUT="$RESULTS/${STAMP}-load-slo.json"
  cat > "$OUT" <<EOF
{
  "stamp": "$STAMP",
  "outcome": "DRY_SMOKE",
  "harness": "jmeter-ws-model",
  "clients": $CLIENTS,
  "durationSec": $DURATION,
  "tpsFloor": $TPS_FLOOR,
  "p95CeilingUs": $P95_CEIL,
  "p99CeilingUs": $P99_CEIL,
  "errorRateCeiling": $ERR_CEIL,
  "tps": null,
  "p50Us": null,
  "p95Us": null,
  "p99Us": null,
  "errorRate": null,
  "jmeterHome": "$JMETER_HOME",
  "jmx": "grid-sql-jmeter/grid-sql-load.jmx",
  "samplerJar": "grid-sql-jmeter/target/grid-sql-jmeter-1.0-SNAPSHOT.jar",
  "classpathEntries": $CP_COUNT,
  "reports": {
    "uiListeners": ["Aggregate Report", "Summary Report", "Graph Results", "Response Time Graph"],
    "htmlDashboard": "JMeter HTML Dashboard Report (-e -o)"
  },
  "notes": "Dry smoke: built sampler + staged classpath; skipped JMeter run / SLO gate. Calm host only."
}
EOF
  echo "Dry smoke OK - wrote $OUT"
  exit 0
fi

JMETER_BIN="$JMETER_HOME/bin/jmeter"
if [[ ! -x "$JMETER_BIN" && -f "$JMETER_HOME/bin/jmeter.sh" ]]; then
  JMETER_BIN="$JMETER_HOME/bin/jmeter.sh"
fi
if [[ ! -f "$JMETER_BIN" ]]; then
  echo "Missing jmeter launcher under $JMETER_HOME/bin" >&2
  exit 1
fi

JTL="$WORK/${STAMP}-load-slo.jtl"
JLOG="$WORK/${STAMP}-jmeter.log"
HTML_DIR="$WORK/${STAMP}-html-report"
rm -f "$JTL"
rm -rf "$HTML_DIR"

JM_FLAGS=( -n -t "$JMX" -l "$JTL" -j "$JLOG"
  "-Juser.classpath=$CP"
  "-Jthreads=$CLIENTS"
  "-JdurationSec=$DURATION"
  "-JrampSec=$RAMP_SEC"
  "-JGRID_URL=$GRID_URL"
  "-JUSER=$USER"
  "-JPASSWORD=$PASSWORD"
  "-JREPORT_DIR=$REPORT_DIR"
)
if [[ "$HTML_REPORT" == "1" ]]; then
  JM_FLAGS+=( -e -o "$HTML_DIR" )
fi

(
  cd "$JMETER_HOME/bin"
  "$JMETER_BIN" "${JM_FLAGS[@]}"
) || echo "WARN: JMeter non-zero exit - see $JLOG" >&2

if [[ ! -f "$JTL" ]]; then
  echo "JMeter did not write JTL: $JTL" >&2
  exit 1
fi

# Thin SLO gate from JTL (awk) — UI/HTML reports are the real report surface.
read -r TOTAL OK FAIL TPS P50_US P95_US P99_US ERR_RATE <<EOF
$(awk -F',' -v dur="$DURATION" '
BEGIN { total=0; ok=0; fail=0; n=0 }
NR==1 { next }
$3 ~ /SETUP/ { next }
{
  total++
  el=$2+0
  if (tolower($8)=="true") { ok++; e[n++]=el } else { fail++ }
}
function pct(p,   r) {
  if (n==0) return "null"
  r = int((p/100.0)*n + 0.999999) - 1
  if (r < 0) r = 0
  if (r >= n) r = n - 1
  return sprintf("%.3f", e[r]*1000.0)
}
END {
  if (n>0) {
    for (i=0;i<n;i++) for (j=i+1;j<n;j++) if (e[i]>e[j]) { t=e[i]; e[i]=e[j]; e[j]=t }
  }
  tps = (ok>0) ? sprintf("%.3f", ok/(dur<1?1:dur)) : "0"
  err = (total>0) ? sprintf("%.6f", fail/total) : "1"
  print total, ok, fail, tps, pct(50), pct(95), pct(99), err
}
' "$JTL")
EOF

PASS=1
awk -v tps="$TPS" -v floor="$TPS_FLOOR" -v p95="$P95_US" -v c95="$P95_CEIL" \
    -v p99="$P99_US" -v c99="$P99_CEIL" -v err="$ERR_RATE" -v cerr="$ERR_CEIL" -v tot="$TOTAL" '
BEGIN {
  ok = (tot+0 > 0) && (tps+0 >= floor+0) && (p95 != "null") && (p99 != "null") \
       && (p95+0 <= c95+0) && (p99+0 <= c99+0) && (err+0 <= cerr+0)
  exit(ok ? 0 : 1)
}' || PASS=0

OUTCOME="FAIL"
[[ "$PASS" == "1" ]] && OUTCOME="PASS"

HTML_JSON="null"
if [[ -d "$HTML_DIR" ]]; then
  HTML_JSON="\"$HTML_DIR\""
fi

OUT="$RESULTS/${STAMP}-load-slo.json"
cat > "$OUT" <<EOF
{
  "stamp": "$STAMP",
  "outcome": "$OUTCOME",
  "harness": "jmeter-ws-model",
  "clients": $CLIENTS,
  "durationSec": $DURATION,
  "tpsFloor": $TPS_FLOOR,
  "p95CeilingUs": $P95_CEIL,
  "p99CeilingUs": $P99_CEIL,
  "errorRateCeiling": $ERR_CEIL,
  "tps": $TPS,
  "p50Us": $P50_US,
  "p95Us": $P95_US,
  "p99Us": $P99_US,
  "errorRate": $ERR_RATE,
  "samples": $TOTAL,
  "successSamples": $OK,
  "failedSamples": $FAIL,
  "gridUrl": "$GRID_URL",
  "jtl": "$JTL",
  "reports": {
    "aggregateReportCsv": "$REPORT_DIR/aggregate-report.csv",
    "summaryReportCsv": "$REPORT_DIR/summary-report.csv",
    "htmlDashboard": $HTML_JSON,
    "uiListeners": ["Aggregate Report", "Summary Report", "Graph Results", "Response Time Graph"]
  },
  "notes": "Calm host only. Open plan in JMeter GUI for Aggregate/Summary Report. CLI HTML Dashboard via -e -o."
}
EOF

echo "Wrote $OUT outcome=$OUTCOME tps=$TPS p95Us=$P95_US p99Us=$P99_US errorRate=$ERR_RATE"
[[ -d "$HTML_DIR" ]] && echo "HTML Dashboard: $HTML_DIR/index.html"
echo "Aggregate/Summary CSV: $REPORT_DIR"
[[ "$OUTCOME" == "PASS" ]] || exit 1
exit 0