# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent `:valid? true`).

## Latest stamp (ASYNC_SHIP)

| Field | Value |
|-------|--------|
| stamp | `2026-10-05-gates-unclean-a3fix` |
| date | 2026-10-05T10:45:08+03:00 |
| git | unknown |
| host | DESKTOP-4IC511D |
| mode | ASYNC_SHIP (async) |
| outcome | `PASS` |
| register | n/a |
| append | PASS |
| chaos | unclean-revive |
| notes | FULL lein chaos=unclean-revive time-limit=60 workloads=append; register=n/a; append=PASS; join=n/a |

Multi-host SQL URL:

```
grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public
```

See [README.md](README.md). Coverage: [../COVERAGE.md](../COVERAGE.md). Parent 1-DC: [../RESULTS.md](../RESULTS.md).

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
| `2026-10-04-multidc-unclean-revive-p2` | ASYNC_SHIP | PASS | PASS | PASS | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=60 workloads=register,append; register=PASS; append=PASS; join=n/a |
| `2026-10-04-2104-jepsen-I-ff-r1` | SYNC_VOTERS_ACROSS_DC | - | - | FAIL | BLOCKED/ERROR: compose up failed: 1 |
| `2026-10-04-2312-hunt-G-nochao-r1` | SYNC_VOTERS_ACROSS_DC | - | - | FAIL | BLOCKED/ERROR: compose up failed: 1 |
| `2026-10-04-2317-hunt-G-nochao-r1` | SYNC_VOTERS_ACROSS_DC | - | - | FAIL | BLOCKED/ERROR: compose up failed: 1 |
| `2026-10-04-2318-hunt-G-nochao-r1` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2318-hunt-G-nochao-r2` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2318-hunt-G-nochao-r3` | SYNC_VOTERS_ACROSS_DC | - | - | FAIL | BLOCKED/ERROR: compose up failed: 1 |
| `2026-10-04-2318-hunt-G-nochao-r4` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2318-hunt-G-nochao-r5` | SYNC_VOTERS_ACROSS_DC | - | - | FAIL | BLOCKED/ERROR: compose up failed: 1 |
| `2026-10-04-2318-hunt-G-nochao-r6` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2318-hunt-G-nochao-r7` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2318-hunt-G-nochao-r8` | SYNC_VOTERS_ACROSS_DC | - | - | FAIL | BLOCKED/ERROR: compose up failed: 1 |
| `2026-10-04-2347-hunt-G-nochao-r1` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2347-hunt-G-nochao-r2` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2347-hunt-G-nochao-r3` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2347-hunt-G-nochao-r4` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2347-hunt-G-nochao-r5` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2347-hunt-G-nochao-r6` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2347-hunt-G-nochao-r7` | SYNC_VOTERS_ACROSS_DC | n/a | PASS (:valid? true) | PASS | register=n/a; append=PASS (:valid? true); time-limit=60; workloads=append |
| `2026-10-04-2347-hunt-G-nochao-r8` | SYNC_VOTERS_ACROSS_DC | - | - | FAIL | BLOCKED/ERROR: compose up failed: 1 |
| `2026-10-05-jepsen-edge-matrix-multidc-unclean-revive` | ASYNC_SHIP | PASS | PASS | PASS | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=60 workloads=register,append; register=PASS; append=PASS; join=n/a |
| `2026-10-05-jepsen-edge-matrix-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; workloads=register,append |
| `2026-10-05-jepsen-edge-matrix-multidc-sync-chaos` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; workloads=register,append |
| `2026-10-05-jepsen-edge-matrix-multidc-async-nochao` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; workloads=register,append |
| `2026-10-05-jepsen-edge-matrix-multidc-sync-nochao` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=60; workloads=register,append |
| `2026-10-05-jepsen-edge-matrix-multidc-async-swarm` | ASYNC_SHIP+SWARM | n/a | PASS | PASS | FULL lein chaos=swarm-bounce+dc-link+kill-voter time-limit=60 workloads=append; register=n/a; append=PASS; join=n/a |
| `2026-10-05-jepsen-edge-matrix-multidc-async-join` | ASYNC_SHIP | n/a | PASS | PASS | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=60 workloads=join; register=n/a; append=PASS; join=PASS |
| `2026-10-05-gates-unclean-p0` | ASYNC_SHIP | PASS | FAIL | FAIL | CLASS=elle; FULL lein chaos=unclean-revive time-limit=60 workloads=register,append; register=PASS; append=FAIL; join=n/a (compose retry recovered; not harness) |
| `2026-10-05-gates-unclean-a3fix` | ASYNC_SHIP | n/a | PASS | PASS | FULL lein chaos=unclean-revive time-limit=60 workloads=append; register=n/a; append=PASS; join=n/a |
