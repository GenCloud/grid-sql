# Witness Jepsen RESULTS

Honest PASS/FAIL after Docker+lein Witness overlay (never invent :valid? true).

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | ``2026-10-05-solo-witness-chaos-r2`` |
| date | 2026-10-05T14:20:56.1481844+03:00 |
| git | c2aca8b |
| host | DESKTOP-4IC511D |
| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |
| outcome | ``PASS`` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-dc-a+revive (Witness overlay) |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| health | /health/liveness (Actuator base-path /) |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=22 info=2 ok=191 | read: n=71 p50=5.075ms p95=11.104ms p99=21.143ms | write: n=78 p50=24.962ms p95=43.189ms p99=57.203ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=21 info=0 ok=206 | read: n=0 | write: n=0 | txn_r: n=49 p50=5.751ms p95=16.558ms p99=57.890ms | txn_append: n=108 p50=18.471ms p95=39.778ms p99=66.555ms |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |

## History

| stamp | register | append | outcome | notes |
|-------|----------|--------|---------|-------|
| stamp | ``2026-10-05-solo-witness-chaos`` |
| outcome | ``FAIL`` |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| stamp | ``2026-10-05-jepsen-edge-matrix-witness-chaos`` |
| outcome | ``FAIL`` |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
| outcome | ``FAIL`` |
| multi-host SQL | ``grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public`` |
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
| ``2026-10-05-jepsen-edge-matrix-witness-chaos`` | - | - | FAIL | BLOCKED/ERROR: compose up failed after retries |
| ``2026-10-05-solo-witness-chaos`` | - | - | FAIL | BLOCKED/ERROR: compose up failed after retries |
| ``2026-10-05-solo-witness-chaos-r2`` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |

Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Debt: TD-HA-001.
