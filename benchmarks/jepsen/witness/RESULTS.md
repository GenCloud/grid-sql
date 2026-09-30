# Witness Jepsen RESULTS

Honest PASS/FAIL after Docker+lein Witness overlay (never invent `:valid? true`).

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | `2026-09-30-multidc-unclean-revive-evidence` |
| date | 2026-09-30T10:56:44+03:00 |
| git | unknown |
| host | DESKTOP-4IC511D |
| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |
| outcome | `PASS` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-dc-a+revive (Witness overlay) |
| multi-host SQL | `grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15437/public` |
| health | `/health/liveness` (Actuator base-path `/`) |
| latency (ok-ops, warmup 10s) | n/a |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |

## History

| stamp | register | append | outcome | notes |
|-------|----------|--------|---------|-------|
| `2026-09-30-multidc-unclean-revive-evidence` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; Witness w1 overlay |

Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Coverage: [../COVERAGE.md](../COVERAGE.md). Debt: TD-HA-001.
