# Witness Jepsen RESULTS

Honest PASS/FAIL after Docker+lein Witness overlay (never invent :valid? true).

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | ``2026-09-29-witness-chaos`` |
| date | 2026-09-29T19:43:32.4768547+03:00 |
| git | 1210357 |
| host | DESKTOP-4IC511D |
| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |
| outcome | ``PASS`` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-dc-a+revive (Witness overlay) |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| health | /health/liveness (Actuator base-path /) |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=4 info=1 ok=187 | read: n=69 p50=1.951ms p95=2.710ms p99=3.765ms | write: n=67 p50=7.199ms p95=20.229ms p99=29.150ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=28 info=1 ok=183 | read: n=0 | write: n=0 | txn_r: n=57 p50=2.377ms p95=3.314ms p99=3.388ms | txn_append: n=79 p50=7.174ms p95=22.445ms p99=31.088ms |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |

## History

| stamp | register | append | outcome | notes |
|-------|----------|--------|---------|-------|
| stamp | `2026-09-21-witness-chaos` |
| outcome | `FAIL` |
| multi-host SQL | `grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public` |
| health | `/health/liveness` (Actuator base-path `/`) |
| `2026-09-21-witness-chaos` | PASS (:valid? true) | FAIL | FAIL | register=PASS (:valid? true); append=FAIL; time-limit=60; Witness w1 overlay |
| ``2026-09-29-witness-chaos`` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |

Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Debt: TD-HA-001.
