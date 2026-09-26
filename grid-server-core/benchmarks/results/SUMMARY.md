# OSS compare SUMMARY

- Stamp: `2026-09-26-select-star-opt`
- Generated: 2026-09-26T13:17:05.6995439+03:00
- Overall gate: PASS (measured tracks)
- vs-baseline: `2026-09-14-followup` (tolerance +/-5%) - SQL-rewrite quality gate
- Harness: SQL-first TableStore (selectKeys / putIndexed / RowEncoder); query Gate vs H2 is informational when keys-only
- Known ceiling: EQ+ORDER+LIMIT vs H2 Gate FAIL is informational - hard Overall / encode / WAL / IMDG / sealed gates unchanged
- Sealed hard gate: rows=100000 (+1000000 if present); rows=1000 smoke RAN only; hybrid.pkGet/whereEq <= max(disk,memory)x1.15 (dual-residency); disk.whereEq <= 2.5x memory
- Sealed invariant: sealedPartitionScan expected = 0; an index miss returns empty without partition scan

## Gates

| Track | Ours | OSS | Gate | vs-baseline |
|-------|------|-----|------|-------------|
| query EQ+LIMIT | 1.122 us | 2.447 us H2 | PASS | PASS |
| query EQ+ORDER+LIMIT | 2.238 us | 2.653 us H2 | PASS | PASS |
| encode logical vs Kryo | 45.9 ns | 49.3 ns Kryo | PASS | PASS |
| encode logical vs FST | 45.9 ns | 397.8 ns FST | PASS | PASS |
| WAL batch vs RocksDB | 51.984 | 557.634 Rocks | PASS | PASS |
| sealed disk.pkGet / memory rows=100000 | 0.137 us | 0.178 us memory | PASS | n/a |
| sealed disk.whereEq / memory rows=100000 | 7.119 us | 5.028 us memory | PASS | n/a |
| sealed hybrid.pkGet <= disk rows=100000 | 0.180 us | 0.178 us memory (+15% dual-residency) | PASS | n/a |
| sealed hybrid.whereEq <= disk rows=100000 | 5.180 us | 7.119 us disk (+15% dual-residency) | PASS | n/a |
| ORCHID durable | 1746.4 us/op | etcd (host) | RAN | n/a |

JSON: `.\grid-server-core\benchmarks\results/2026-09-26-select-star-opt-*.json`

Docs claim wins only when Gate=PASS (encode/WAL/IMDG). Query Gate vs H2 may FAIL while vs-baseline PASSes (keys-only harness).
**EQ+ORDER+LIMIT vs H2:** documented informational ceiling (near-parity FAIL) - not a hard Overall flip; hard OSS gates must stay as-is.
Overall FAIL if vs-baseline FAIL or encode/WAL/IMDG Gate FAIL. Do not stamp SQL rewrite PASS until gated tracks + Jepsen nochao hold.
