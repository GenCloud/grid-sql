# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent `:valid? true`).

## Latest stamp (SYNC_VOTERS_ACROSS_DC)

| Field | Value |
|-------|--------|
| stamp | `2026-10-03-multidc-unclean-revive-restamp` |
| date | 2026-10-03T13:42:02+03:00 |
| git | unknown |
| host | DESKTOP-4IC511D |
| mode | SYNC_VOTERS_ACROSS_DC (sync-voters) |
| outcome | `PASS` |
| register | PASS |
| append | PASS |
| chaos | unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a |
| notes | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=45 workloads=register,append; register=PASS; append=PASS; join=n/a |

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
| `2026-10-03-jepsen-edge-matrix-multidc-async-chaos` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-jepsen-edge-matrix-multidc-sync-chaos` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | FAIL | FAIL | register=PASS (:valid? true); append=FAIL; time-limit=45 |
| `2026-10-03-jepsen-edge-matrix-multidc-async-nochao` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-jepsen-edge-matrix-multidc-sync-nochao` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-jepsen-edge-matrix-multidc-async-swarm` | ASYNC_SHIP+SWARM | n/a | PASS | PASS | FULL lein chaos=swarm-bounce+dc-link+kill-voter time-limit=45 workloads=append; register=n/a; append=PASS; join=n/a |
| `2026-10-03-jepsen-edge-matrix-multidc-async-join` | ASYNC_SHIP | n/a | PASS | PASS | FULL lein chaos=join-shards+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=45 workloads=join; register=n/a; append=PASS; join=PASS |
| `2026-10-03-jepsen-edge-matrix-multidc-unclean-revive` | SYNC_VOTERS_ACROSS_DC | n/a | FAIL | FAIL | FULL lein chaos=join-shards+unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=45 workloads=join; register=n/a; append=FAIL; join=FAIL |
| `2026-10-03-multidc-sync-chaos-restamp` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=45 |
| `2026-10-03-multidc-unclean-revive-restamp` | SYNC_VOTERS_ACROSS_DC | PASS | PASS | PASS | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=45 workloads=register,append; register=PASS; append=PASS; join=n/a |
