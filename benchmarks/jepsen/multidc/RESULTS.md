# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent `:valid? true`).

## Latest stamp (ASYNC_SHIP)

| Field | Value |
|-------|--------|
| stamp | `2026-09-30-sess-multidc` |
| date | 2026-09-30T17:03:24.4059620+03:00 |
| git | dac901a |
| host | DESKTOP-4IC511D |
| mode | `ASYNC_SHIP` (topology 3+2) |
| outcome | `PASS` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-voter+kill-dc-a+revive-dc-a |
| multi-host SQL | `grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public` |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=0 info=0 ok=135 | read: n=58 p50=2.281ms p95=3.285ms p99=3.976ms | write: n=50 p50=8.152ms p95=23.624ms p99=26.617ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=0 info=4 ok=103 | read: n=0 | write: n=0 | txn_r: n=30 p50=2.522ms p95=4.099ms p99=4.720ms | txn_append: n=29 p50=11.640ms p95=41.101ms p99=79.559ms |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=30 |

## History

| stamp | mode | register | append | outcome | notes |
|-------|------|----------|--------|---------|-------|
| `2026-09-30-multidc-unclean-revive-evidence` | ASYNC_SHIP | PASS | PASS | PASS | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=60; register=PASS; append=PASS |
| `2026-09-30-sess-multidc` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=30 |

Parent 1-DC: [../RESULTS.md](../RESULTS.md).
