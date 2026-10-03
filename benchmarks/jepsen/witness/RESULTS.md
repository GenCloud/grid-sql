# Witness Jepsen RESULTS

Honest PASS/FAIL after Docker+lein Witness overlay (never invent :valid? true).

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | ``2026-10-03-jepsen-edge-matrix-witness-chaos`` |
| date | 2026-10-03T20:47:35.7934297+03:00 |
| git | 338c881 |
| host | DESKTOP-4IC511D |
| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |
| outcome | ``PASS`` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-dc-a+revive (Witness overlay) |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| health | /health/liveness (Actuator base-path /) |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=1 info=4 ok=80 | read: n=41 p50=3.266ms p95=5.117ms p99=6.185ms | write: n=33 p50=11.930ms p95=48.032ms p99=63.381ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=2 info=10 ok=29 | read: n=0 | write: n=0 | txn_r: n=10 p50=7.037ms p95=12.129ms p99=12.129ms | txn_append: n=13 p50=15.743ms p95=2045.785ms p99=2045.785ms |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45; Witness w1 overlay |

## History

| stamp | register | append | outcome | notes |
|-------|----------|--------|---------|-------|
| outcome | ``PASS`` |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| stamp | ``2026-10-02-jepsen-edge-matrix-witness-chaos`` |
| outcome | ``PASS`` |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| outcome | ``PASS`` |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| outcome | ``PASS`` |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| stamp | `2026-09-30-multidc-unclean-revive-evidence` |
| outcome | `PASS` |
| multi-host SQL | `grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public` |
| health | `/health/liveness` (Actuator base-path `/`) |
| `2026-09-30-multidc-unclean-revive-evidence` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |
| ``2026-10-02-jepsen-edge-matrix-witness-chaos`` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=40; Witness w1 overlay |
| ``2026-10-03-jepsen-edge-matrix-witness-chaos`` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45; Witness w1 overlay |

Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Debt: TD-HA-001.
