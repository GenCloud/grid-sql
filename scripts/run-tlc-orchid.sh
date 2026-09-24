#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
JAR="$TOOLS/tla2tools.jar"
URL="${TLA2TOOLS_URL:-https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar}"
HEAVY=0
REGION_CLAIM=0
for arg in "$@"; do
  case "$arg" in
    --heavy|-Heavy) HEAVY=1 ;;
    --region-claim|-RegionClaim) REGION_CLAIM=1 ;;
  esac
done

mkdir -p "$TOOLS" "$ROOT/docs/spec/orchid/tlc-out"
if [[ ! -f "$JAR" ]]; then
  echo "Downloading tla2tools.jar ..."
  curl -fsSL -o "$JAR" "$URL" || wget -q -O "$JAR" "$URL"
fi

cd "$ROOT/docs/spec/orchid"

run_tlc() {
  local spec="$1" cfg="$2" log="$3"
  echo "Running TLC on $spec ($cfg) ..."
  java -XX:+UseParallelGC -cp "$JAR" tlc2.TLC -config "$cfg" -workers auto -maxSetSize 1000000 "$spec" | tee "tlc-out/$log"
  grep -q "Model checking completed. No error has been found." "tlc-out/$log"
  echo "TLC OK: $spec ($cfg)"
}

# Hard gate: fast MaxSeq=3
run_tlc OrchidLog.tla OrchidLog.cfg last-run.log
run_tlc OrchidLogMultiDc.tla OrchidLogMultiDc.cfg last-run-multidc.log
if [[ "$HEAVY" -eq 1 ]]; then
  run_tlc OrchidLog.tla OrchidLog-heavy.cfg last-run-heavy.log
fi
# Always hard: RegionClaim (TD-SPEC-001)
run_tlc RegionClaim.tla RegionClaim.cfg last-run-region-claim.log
extra=""
[[ "$HEAVY" -eq 1 ]] && extra+=" + heavy"
echo "TLC OK (1-DC + multi-DC + RegionClaim${extra})"
