# Witness Jepsen RESULTS

Honest PASS/FAIL after Docker+lein Witness overlay (never invent :valid? true).

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | ``2026-10-14-jepsen-edge-matrix-witness-chaos`` |
| date | 2026-10-14T19:18:35.2668591+03:00 |
| git | 32f92bc |
| host | local |
| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |
| outcome | ``PASS`` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-dc-a+revive (Witness overlay) |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| health | /health/liveness (Actuator base-path /) |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=0 info=1 ok=293 | read: n=128 p50=3.085ms p95=3.895ms p99=4.512ms | write: n=140 p50=17.659ms p95=38.787ms p99=44.591ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=0 info=185 ok=104 | read: n=0 | write: n=0 | txn_r: n=91 p50=9.142ms p95=13.224ms p99=14.749ms | txn_append: n=0 |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |

## History

| stamp | register | append | outcome | notes |
|-------|----------|--------|---------|-------|
| stamp | ``2026-10-03-jepsen-edge-matrix-witness-chaos`` |
| outcome | ``PASS`` |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
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
| ``2026-10-14-jepsen-edge-matrix-witness-chaos`` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |

Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Debt: TD-HA-001.
