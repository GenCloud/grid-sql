#!/usr/bin/env bash
# Summarize JaCoCo instruction + branch coverage from aggregate CSV.
# Usage (after mvn -pl grid-coverage -am verify):
#   ./scripts/jacoco-summary.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CSV="${1:-$ROOT/grid-coverage/target/site/jacoco-aggregate/jacoco.csv}"
if [[ ! -f "$CSV" ]]; then
  echo "JaCoCo CSV not found: $CSV" >&2
  echo "Run: mvn -pl grid-coverage -am verify" >&2
  exit 1
fi

python3 - "$CSV" "$ROOT/docs/assets/badges/jacoco-shields.json" <<'PY'
import csv, json, os, sys
from collections import defaultdict

path = sys.argv[1]
badge_path = sys.argv[2]
instr_m = instr_c = branch_m = branch_c = 0
by = defaultdict(lambda: [0, 0, 0, 0])  # im, ic, bm, bc

with open(path, newline="", encoding="utf-8") as f:
    r = csv.reader(f)
    next(r, None)
    for row in r:
        if len(row) < 7:
            continue
        g = row[0]
        im, ic, bm, bc = map(int, row[3:7])
        instr_m += im; instr_c += ic; branch_m += bm; branch_c += bc
        by[g][0] += im; by[g][1] += ic; by[g][2] += bm; by[g][3] += bc

def pct(c, m):
    t = c + m
    return 100.0 if t == 0 else round(100.0 * c / t, 2)

def shield_color(p):
    if p >= 80.0:
        return "brightgreen"
    if p >= 60.0:
        return "yellowgreen"
    if p >= 40.0:
        return "yellow"
    return "orange"

print(f"JaCoCo aggregate: {path}")
print()
print(f"{'Module':<28} {'Instruction%':>10} {'Branch%':>10}")
print("-" * 52)
for g in sorted(by):
    im, ic, bm, bc = by[g]
    print(f"{g:<28} {pct(ic, im):>10.2f} {pct(bc, bm):>10.2f}")
print("-" * 52)
i_pct = pct(instr_c, instr_m)
b_pct = pct(branch_c, branch_m)
print(f"{'TOTAL':<28} {i_pct:>10.2f} {b_pct:>10.2f}")
print()
print(f"Instructions: covered={instr_c} missed={instr_m} total={instr_c + instr_m}")
print(f"Branches:     covered={branch_c} missed={branch_m} total={branch_c + branch_m}")
print("HTML: grid-coverage/target/site/jacoco-aggregate/index.html")

os.makedirs(os.path.dirname(badge_path), exist_ok=True)
msg = f"{i_pct:.2f}".rstrip("0").rstrip(".") + "%"
if msg == "%":
    msg = "0%"
payload = {
    "schemaVersion": 1,
    "label": "coverage",
    "message": msg if msg.endswith("%") else f"{i_pct:.2f}%",
    "color": shield_color(i_pct),
}
# Prefer exact two-decimal style matching prior stamp (74.35%)
payload["message"] = f"{i_pct:.2f}%"
with open(badge_path, "w", encoding="utf-8", newline="\n") as out:
    json.dump(payload, out, indent=2)
    out.write("\n")
print(f"Badge:  docs/assets/badges/jacoco-shields.json ({payload['message']})")
PY
