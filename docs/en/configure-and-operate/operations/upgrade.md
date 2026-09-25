# Upgrade and node restart

How to change the binary or restart a node without losing `dataDir` and without dual-writer. Short runbook; HA and Multi-DC details on the linked pages.

## Before upgrade

1. Confirm `dataDir` is local per node (not NFS/SAN for the cluster).
2. With durability: `fsync: true`; take a fresh PITR **base** at a watermark if needed ([PITR](pitr.md)).
3. Note who is the current writer (`writerEligible` / client meta).
4. Application ready for `PROMOTE_NOTIFY` / `rediscoverWriter()` — [promote](ha-promote.md).
5. Record jar/image versions “before” and target “after” (for binary rollback).

## Rolling restart (replication ring)

Order: **non-writers** (replicas) first, current writer last (or hand off the role first, then upgrade the former writer).

On each node:

1. Drain traffic / wait until this process has no in-flight own writes.
2. Clean stop (SIGTERM / Spring shutdown) — force mmap and atomic `.wpos`.
3. Replace jar / image; **same** `dataDir` and shard / `cluster-id` config.
4. Start; wait for readiness UP and ORCHID sync.
5. Check catch-up (`applyLag`), then the next node.

### Mixed-version ring

During a rolling upgrade the ring may briefly run **two binary versions**. That is expected for a rolling restart on a compatible data layout. Do **not** leave a mixed ring as a permanent state: finish every node, then verify.

| Combination | Allowed briefly? | Notes |
|-------------|------------------|-------|
| Same major sealed/OpLog layout, two jar versions | Yes, during rolling only | Finish every node; then one version |
| Incompatible `dataDir` layout across nodes | No | Take PITR base first; migrate one layout family at a time |
| Two processes / two binaries on one `dataDir` | Never | Incident — stop the extra process |

After each node comes back:

1. Actuator readiness UP; with replication — ORCHID synced (`orchidSynced` / `orchid_r` above admission).
2. On the intended writer: `writerEligible: true` and client meta match (`PROMOTE_NOTIFY` / AUTH) — [promote](ha-promote.md).
3. Replicas: `applyLagStale` false (or within your accepted lag policy) before reading from them.
4. One smoke write + read on the application path (not HTTP `/replication/compare` as writer discovery).

`SIGKILL` skips hooks — the next open replays a torn OpLog tail; that is supported, but slower.

## Solo durable node

1. Stop the process.
2. Replace the binary; keep `dataDir`.
3. Start; with `hydrate-mode: LAZY` an “empty” RAM is normal — truth is sealed + OpLog tail.

## Schema epoch, layout, and clients

- Schema changes (DDL) are recorded in the catalog; clients with stale session metadata reconnect.
- Duplex / codec `schema-epoch` in YAML is not ORCHID seq — see [Duplex](../../performance/perf-duplex.md). Do **not** bump it casually on a live `dataDir` that peers still read with the old epoch.
- After writer change the client must **not** rotate the next host in the URL by hand.
- Major `dataDir` layout change (incompatible sealed/OpLog format) — take base + verify archive first, then follow the binary migration notes. Keep one layout family per cluster until every node is upgraded.
- Do not change `cluster-id`, shard count of existing tables, or share one `dataDir` across two binaries during upgrade.

## Binary rollback

| Situation | Action |
|-----------|--------|
| New binary fails to start / readiness DOWN | Restore previous jar/image on the **same** `dataDir`; do not start a second process |
| Data damaged after a bad start | Do not keep writing — [PITR](pitr.md) on a copy, then rejoin |
| Need state “as of an hour ago” | Only PITR to `--until-seq`, not jar rollback |

## When a PITR base is needed before upgrade

| Situation | Base |
|-----------|------|
| Ordinary rolling on the same major data layout | Desirable on schedule, not required on every restart |
| `dataDir` migration, disk doubt, host change | Take base + verify archive **before** work |
| Roll back “as of an hour ago” | Only PITR to `--until-seq` |

## Do not

- Two processes on one `dataDir`.
- Upgrade every node at once without catch-up.
- Disable replication / fsync “for the upgrade window” without an explicit loss window.

**Related:** [failures](failures.md), [PITR](pitr.md), [durability](../configuration/durability.md), [Compose](deploy-compose.md).

## Common failures during upgrade

| Symptom | Action |
|---------|--------|
| Readiness DOWN after start with replication | Wait for ORCHID sync; check `orchid_r` / peers — do not send traffic |
| Client still writes the old host | `rediscoverWriter()` / `PROMOTE_NOTIFY`, not the next URL host |
| Slow start after `SIGKILL` | Torn OpLog-tail replay — expected; use SIGTERM for the next stop |
| `applyLagStale` on the first replica after upgrade | Wait for catch-up before reading from it |
| Two processes on one `dataDir` | Stop the extra process immediately; this is an incident, not an upgrade ([failures](failures.md)) |

After a full rolling restart: every node on the target version; one writer; smoke write/read on the application path.

### Post-upgrade smoke

| Check | Expect |
|-------|--------|
| Actuator readiness UP on every node | SQL listening; with replication — ORCHID synced |
| Exactly one `writerEligible: true` | Client meta matches (`PROMOTE_NOTIFY` / AUTH) |
| One application write + read | Path OK — not `/replication/compare` as writer discovery |
| Replicas before read traffic | `applyLagStale` false (or within policy) |
| Users / AUTH (if enabled) | `privileges.meta` present on **every** SQL node apps may hit |
