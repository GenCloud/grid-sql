# Witness Jepsen RESULTS

Honest PASS/FAIL after Docker+lein Witness overlay (never invent :valid? true).

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | ``2026-10-30-jepsen-edge-matrix-witness-chaos`` |
| date | 2026-10-30T01:22:46.1845962+03:00 |
| git | 2557415 |
| host | local |
| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |
| outcome | ``PASS`` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-dc-a+revive (Witness overlay) |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| health | /health/liveness (Actuator base-path /) |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=0 info=0 ok=224 | read: n=85 p50=2.896ms p95=4.367ms p99=4.919ms | write: n=89 p50=14.142ms p95=36.470ms p99=42.567ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=0 info=147 ok=84 | read: n=0 | write: n=0 | txn_r: n=68 p50=5.828ms p95=9.142ms p99=9.573ms | txn_append: n=0 |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45; Witness w1 overlay |

## History

| stamp | register | append | outcome | notes |
|-------|----------|--------|---------|-------|
| stamp | ``2026-10-14-jepsen-edge-matrix-witness-chaos`` |
| outcome | ``PASS`` |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
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
| ``2026-10-30-jepsen-edge-matrix-witness-chaos`` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45; Witness w1 overlay |

Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Debt: TD-HA-001.
