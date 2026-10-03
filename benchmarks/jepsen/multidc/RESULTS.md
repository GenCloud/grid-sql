# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent `:valid? true`).

## Latest stamp (ASYNC_SHIP)

| Field | Value |
|-------|--------|
| stamp | `2026-10-03-preland-r4-multidc-async-chaos` |
| date | 2026-10-03T22:56:31.6483562+03:00 |
| git | 338c881 |
| host | DESKTOP-4IC511D |
| mode | `ASYNC_SHIP` (topology 3+2) |
| outcome | `PASS` |
| register | PASS (:valid? true) |
| append | PASS (:valid? true) |
| chaos | dc-link+kill-voter+kill-dc-a+revive-dc-a |
| multi-host SQL | `grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public` |
| latency (ok-ops, warmup 10s) | register: workload=register warmupDrop=10s fail=1 info=0 ok=197 | read: n=74 p50=3.876ms p95=6.920ms p99=15.798ms | write: n=74 p50=14.732ms p95=37.531ms p99=40.816ms | txn_r: n=0 | txn_append: n=0; append: workload=append warmupDrop=10s fail=2 info=0 ok=185 | read: n=0 | write: n=0 | txn_r: n=42 p50=7.604ms p95=10.886ms p99=12.725ms | txn_append: n=89 p50=21.361ms p95=46.992ms p99=49.430ms |
| notes | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |

## History

| stamp | mode | register | append | outcome | notes |
|-------|------|----------|--------|---------|-------|
| `2026-10-02-jepsen-edge-matrix-multidc-async-chaos` | ASYNC_SHIP | - | - | FAIL | BLOCKED/ERROR: D:\workspace\jamoa-grid-cache\benchmarks\jepsen\scripts\wait-writer-eligible.ps1:2 знак:14
| `2026-10-02-jepsen-edge-matrix-multidc-sync-chaos` | SYNC_VOTERS_ACROSS_DC | - | - | FAIL | BLOCKED/ERROR: D:\workspace\jamoa-grid-cache\benchmarks\jepsen\scripts\wait-writer-eligible.ps1:2 знак:14
| `2026-10-02-jepsen-edge-matrix-multidc-async-nochao` | ASYNC_SHIP | - | - | FAIL | BLOCKED/ERROR: D:\workspace\jamoa-grid-cache\benchmarks\jepsen\scripts\wait-writer-eligible.ps1:2 знак:14
| `2026-10-02-jepsen-edge-matrix-multidc-sync-nochao` | SYNC_VOTERS_ACROSS_DC | - | - | FAIL | BLOCKED/ERROR: D:\workspace\jamoa-grid-cache\benchmarks\jepsen\scripts\wait-writer-eligible.ps1:2 знак:14
| `2026-10-02-jepsen-edge-matrix-multidc-unclean-revive` | SYNC_VOTERS_ACROSS_DC | PASS | PASS | PASS | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=40; register=PASS; append=PASS |
| `2026-10-02-jepsen-mdc-restamp-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=40 |
| `2026-10-02-jepsen-mdc-restamp-multidc-sync-chaos` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=40 |
| `2026-10-02-jepsen-mdc-restamp-multidc-async-nochao` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=40 |
| `2026-10-02-jepsen-mdc-restamp-multidc-sync-nochao` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=40 |
| `2026-10-03-multidc-async-swarm-chaos` | ASYNC_SHIP+SWARM | n/a | PASS | PASS | FULL lein chaos=swarm-bounce+dc-link+kill-voter time-limit=40 workloads=append; register=n/a; append=PASS; join=n/a |
| `2026-10-03-multidc-async-join-shards` | ASYNC_SHIP | n/a | PASS | PASS | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=40 workloads=join; register=n/a; append=PASS; join=PASS |
| `2026-10-03-multidc-async-join-shards-fix` | ASYNC_SHIP | n/a | FAIL | FAIL | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=40 workloads=join; register=n/a; append=FAIL; join=FAIL |
| `2026-10-03-multidc-async-join-shards-fix2` | ASYNC_SHIP | n/a | FAIL | FAIL | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=40 workloads=join; register=n/a; append=FAIL; join=FAIL |
| `2026-10-03-multidc-async-join-shards-plan` | ASYNC_SHIP | n/a | FAIL | FAIL | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=40 workloads=join; register=n/a; append=FAIL; join=FAIL |
| `2026-10-03-multidc-async-join-shards-plan2` | ASYNC_SHIP | n/a | FAIL | FAIL | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=60 workloads=join; register=n/a; append=FAIL; join=FAIL |
| `2026-10-03-multidc-async-join-shards-plan3` | ASYNC_SHIP | n/a | PASS | PASS | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=60 workloads=join; register=n/a; append=PASS; join=PASS |
| `2026-10-03-multidc-async-join-shards-plan3b` | ASYNC_SHIP | n/a | PASS | PASS | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=60 workloads=join; register=n/a; append=PASS; join=PASS |
| `2026-10-03-multidc-sync-chaos-restamp` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-multidc-unclean-revive-restamp` | SYNC_VOTERS_ACROSS_DC | PASS | PASS | PASS | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=45 workloads=register,append; register=PASS; append=PASS; join=n/a |
| `2026-10-03-jepsen-edge-matrix-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | FAIL | FAIL | register=PASS (:valid? true); append=FAIL; time-limit=45 |
| `2026-10-03-jepsen-edge-matrix-multidc-sync-chaos` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | FAIL | FAIL | register=PASS (:valid? true); append=FAIL; time-limit=45 |
| `2026-10-03-jepsen-edge-matrix-multidc-async-nochao` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-jepsen-edge-matrix-multidc-sync-nochao` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-jepsen-edge-matrix-multidc-async-swarm` | ASYNC_SHIP+SWARM | n/a | PASS | PASS | FULL lein chaos=swarm-bounce+dc-link+kill-voter time-limit=45 workloads=append; register=n/a; append=PASS; join=n/a |
| `2026-10-03-jepsen-edge-matrix-multidc-async-join` | ASYNC_SHIP | n/a | PASS | PASS | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=45 workloads=join; register=n/a; append=PASS; join=PASS |
| `2026-10-03-jepsen-edge-matrix-multidc-unclean-revive` | ASYNC_SHIP | PASS | PASS | PASS | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=45 workloads=register,append; register=PASS; append=PASS; join=n/a |
| `2026-10-03-multidc-async-chaos-g2-restamp` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-multidc-sync-chaos-g2-restamp` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-g2-hunt-r1-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-g2-hunt-r1-multidc-sync-chaos` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-g2-hunt-r2-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | FAIL | FAIL | register=PASS (:valid? true); append=FAIL; time-limit=45 |
| `2026-10-03-g2-hunt2-r1-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | FAIL | FAIL | register=PASS (:valid? true); append=FAIL; time-limit=45 |
| `2026-10-03-g2-hunt3-r1-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | FAIL | FAIL | register=PASS (:valid? true); append=FAIL; time-limit=45 |
| `2026-10-03-preland-r2-multidc-async-chaos` | ASYNC_SHIP | - | - | FAIL | BLOCKED/ERROR: multidc overlay docker build failed: 1 |
| `2026-10-03-preland-r3-multidc-async-chaos` | ASYNC_SHIP | - | - | FAIL | BLOCKED/ERROR: compose up failed: 1 |
| `2026-10-03-preland-r3-multidc-sync-chaos` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-preland-r3b-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | FAIL | FAIL | register=PASS (:valid? true); append=FAIL; time-limit=45 |
| `2026-10-03-preland-r4-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |

Parent 1-DC: [../RESULTS.md](../RESULTS.md).
