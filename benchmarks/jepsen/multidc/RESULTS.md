# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent `:valid? true`).

## Latest stamp (ASYNC_SHIP)

| Field | Value |
|-------|--------|
| stamp | `2026-09-29-multidc-async-nochao-rerun` |
| date | 2026-09-29T19:22:05.3795412+03:00 |
| git | 1210357 |
| host | DESKTOP-4IC511D |
| mode | `ASYNC_SHIP` (topology 3+2) |
| outcome | `PASS` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | no-nemesis |
| multi-host SQL | `grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public` |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=46 info=7 ok=38 | read: n=12 p50=2.930ms p95=5.477ms p99=5.477ms | write: n=5 p50=20.332ms p95=27.968ms p99=27.968ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=96 info=4 ok=81 | read: n=0 | write: n=0 | txn_r: n=36 p50=3.497ms p95=5.971ms p99=6.052ms | txn_append: n=29 p50=15.074ms p95=42.332ms p99=620.777ms |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60 |

## History

| stamp | mode | register | append | outcome | notes |
|-------|------|----------|--------|---------|-------|
| `multidc-async-skip-docker` | ASYNC_SHIP | - | - | SKIP | Docker daemon unavailable; compose validate and full lein not run. |
| `2026-09-18-multidc-async` | ASYNC_SHIP | - | - | FAIL | BLOCKED/ERROR: compose up failed: 1 |
| `2026-09-18-multidc-sync-voters` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60 |
| `2026-09-29-multidc-async-chaos-rerun` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60 |
| `2026-09-29-multidc-sync-voters-chaos-rerun` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60 |
| `2026-09-29-multidc-async-nochao-rerun` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60 |

Parent 1-DC: [../RESULTS.md](../RESULTS.md).
