# Witness Jepsen RESULTS

Honest PASS/FAIL after Docker+lein Witness overlay (never invent :valid? true).

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | ``2026-10-05-jepsen-edge-matrix-witness-chaos`` |
| date | 2026-10-05T03:41:08.5651171+03:00 |
| git | 770e79c |
| host | DESKTOP-4IC511D |
| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |
| outcome | ``PASS`` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-dc-a+revive (Witness overlay) |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| health | /health/liveness (Actuator base-path /) |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=24 info=2 ok=128 | read: n=55 p50=3.957ms p95=7.007ms p99=8.112ms | write: n=45 p50=17.827ms p95=43.071ms p99=331.950ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=23 info=0 ok=215 | read: n=0 | write: n=0 | txn_r: n=44 p50=6.455ms p95=11.456ms p99=13.479ms | txn_append: n=118 p50=18.003ms p95=40.716ms p99=46.528ms |
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
| ``2026-10-05-jepsen-edge-matrix-witness-chaos`` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |

Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Debt: TD-HA-001.
