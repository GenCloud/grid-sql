# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent `:valid? true`).

## Latest stamp (SYNC_VOTERS_ACROSS_DC)

| Field | Value |
|-------|--------|
| stamp | `2026-09-27-schema-jepsen` |
| date | 2026-09-27T12:43:20.6919237+03:00 |
| git | 95044fe |
| host | DESKTOP-4IC511D |
| mode | `SYNC_VOTERS_ACROSS_DC` (topology 3+2) |
| outcome | `PASS` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-voter+kill-dc-a+revive-dc-a |
| multi-host SQL | `grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public` |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=0 info=5 ok=17 | read: n=7 p50=2.289ms p95=2.399ms p99=2.399ms | write: n=9 p50=20.685ms p95=34.042ms p99=34.042ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=7 info=17 ok=82 | read: n=0 | write: n=0 | txn_r: n=24 p50=2.777ms p95=4.270ms p99=8.848ms | txn_append: n=19 p50=10.241ms p95=24.068ms p99=24.068ms |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=30 |

## History

| stamp | mode | register | append | outcome | notes |
|-------|------|----------|--------|---------|-------|
| `2026-09-22-residuals-gates-multidc` | ASYNC+SYNC | PASS (:valid? true) | PASS (:valid? true) | PASS | residuals calm gates; async+sync stamps `...-multidc-async` / `...-multidc-sync` |
| `2026-09-22-residuals-gates-multidc-async` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=30 |
| `2026-09-22-residuals-gates-multidc-sync` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=30; register concurrency 1 |
| `2026-09-22-v1-gates-multidc` | ASYNC+SYNC | PASS (:valid? true) | PASS (:valid? true) | PASS | calm sequential after Waves 0–2; 1-DC twin `2026-09-22-v1-gates-jepsen-1dc` |
| `2026-09-17-multidc-async` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=60 |
| `2026-09-17-multidc-sync-voters` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=60 |
| `2026-09-18-aqe-residuals` (re-run) | ASYNC+SYNC | PASS (:valid? true) | PASS (:valid? true) | PASS | calm sequential after AQE leftovers |
| `2026-09-20` TD residuals (Phase F) | ASYNC_SHIP then SYNC_VOTERS | PASS (:valid? true) | PASS (:valid? true) | PASS | 1-DC twin `2026-09-20-jepsen-full` |
| `2026-09-18-multidc-sync-voters` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=30 |
| `2026-09-18-multidc-async` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=60 |
| `2026-09-26-select-star-multidc-async` | ASYNC_SHIP | FAIL | PASS (:valid? true) | FAIL | register=FAIL; append=PASS (:valid? true); time-limit=60 |
| `2026-09-26-select-star-multidc-sync` | SYNC_VOTERS_ACROSS_DC | FAIL | PASS (:valid? true) | FAIL | register=FAIL; append=PASS (:valid? true); time-limit=60 |
| `2026-09-26-select-star-multidc-async-r2` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=30 |
| `2026-09-26-select-star-multidc-sync-r2` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=30 |
| `2026-09-27-schema-multidc-async` | ASYNC_SHIP | FAIL | PASS (:valid? true) | FAIL | register=FAIL; append=PASS (:valid? true); time-limit=60 |
| `2026-09-27-schema-jepsen` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=30 |

Parent 1-DC: [../RESULTS.md](../RESULTS.md).
