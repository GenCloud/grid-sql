#!/usr/bin/env bash
# Validate Jepsen COVERAGE A-M cells match bash entrypoints + jepsen-qg.yml matrix.
# Exit 0 = synced; exit 1 = drift. Safe for unit CI (no Docker required).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
COVERAGE="$ROOT/benchmarks/jepsen/COVERAGE.md"
WORKFLOW="$ROOT/.github/workflows/jepsen-qg.yml"
fail=0

require_file() {
  local f="$1"
  if [[ ! -f "$f" ]]; then
    echo "MISSING file: $f" >&2
    fail=1
  fi
}

require_execish() {
  local f="$1"
  require_file "$f"
  if [[ -f "$f" ]] && [[ ! -s "$f" ]]; then
    echo "EMPTY script: $f" >&2
    fail=1
  fi
}

# ID -> relative script path (product entrypoints from COVERAGE.md)
declare -A CELL_SCRIPT=(
  [A]="benchmarks/jepsen/scripts/run-jepsen.sh"
  [B]="benchmarks/jepsen/scripts/run-jepsen-unclean-revive.sh"
  [C]="benchmarks/jepsen/scripts/run-jepsen-nochao.sh"
  [D]="benchmarks/jepsen/multidc/scripts/run-multidc-chaos.sh"
  [E]="benchmarks/jepsen/multidc/scripts/run-multidc-chaos.sh"
  [F]="benchmarks/jepsen/multidc/scripts/run-multidc-nochao.sh"
  [G]="benchmarks/jepsen/multidc/scripts/run-multidc-nochao.sh"
  [H]="benchmarks/jepsen/witness/scripts/run-witness-chaos.sh"
  [I]="benchmarks/jepsen/multidc/scripts/run-multidc-unclean-revive.sh"
  [J]="benchmarks/jepsen/scripts/run-jepsen-swarm.sh"
  [K]="benchmarks/jepsen/scripts/run-jepsen-join.sh"
  [L]="benchmarks/jepsen/multidc/scripts/run-multidc-swarm.sh"
  [M]="benchmarks/jepsen/multidc/scripts/run-multidc-join.sh"
)

declare -A CELL_CONFIG=(
  [A]="1dc-chaos"
  [B]="1dc-unclean-revive"
  [C]="1dc-nochao"
  [D]="multidc-async-chaos"
  [E]="multidc-sync-chaos"
  [F]="multidc-async-nochao"
  [G]="multidc-sync-nochao"
  [H]="witness-chaos"
  [I]="multidc-unclean-revive"
  [J]="1dc-swarm-chaos"
  [K]="1dc-join-shards"
  [L]="multidc-async-swarm"
  [M]="multidc-async-join"
)

require_file "$COVERAGE"
require_file "$WORKFLOW"
require_execish "$ROOT/benchmarks/jepsen/scripts/jepsen-honesty-gate.sh"
require_execish "$ROOT/benchmarks/jepsen/scripts/qg-gate.sh"

for id in A B C D E F G H I J K L M; do
  cfg="${CELL_CONFIG[$id]}"
  script="${CELL_SCRIPT[$id]}"
  require_execish "$ROOT/$script"
  if ! grep -Fq "\`$cfg\`" "$COVERAGE" && ! grep -Fq "| $cfg " "$COVERAGE" && ! grep -Fq "$cfg" "$COVERAGE"; then
    echo "COVERAGE.md missing config token: $cfg (cell $id)" >&2
    fail=1
  fi
  if ! grep -Fq "$cfg" "$WORKFLOW"; then
    echo "jepsen-qg.yml missing matrix config: $cfg (cell $id)" >&2
    fail=1
  fi
done

# Honesty gate must be wired for join profiles.
if ! grep -Fq "jepsen-honesty-gate.sh" "$ROOT/benchmarks/jepsen/scripts/run-jepsen-join.sh"; then
  echo "1dc join script missing honesty-gate call" >&2
  fail=1
fi
if ! grep -Fq "jepsen-honesty-gate.sh" "$ROOT/benchmarks/jepsen/multidc/scripts/multidc-common.sh" \
  && ! grep -Fq "jepsen-honesty-gate.sh" "$ROOT/benchmarks/jepsen/multidc/scripts/run-multidc-join.sh"; then
  # honesty may live in multidc-common (join workloads)
  if ! grep -Fq "jepsen-honesty-gate.sh" "$ROOT/benchmarks/jepsen/multidc/scripts/multidc-common.sh"; then
    echo "multidc join path missing honesty-gate call" >&2
    fail=1
  fi
fi

if [[ "$fail" -ne 0 ]]; then
  echo "Jepsen GHA matrix validation FAILED" >&2
  exit 1
fi
echo "Jepsen GHA matrix validation OK (A-M + honesty-gate + scripts)"
exit 0