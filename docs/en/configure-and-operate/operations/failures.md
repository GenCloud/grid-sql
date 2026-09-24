# Failures and common incidents

On-call scenarios: symptom → action → where to read more. Operator handbook — not a “cluster is ready” claim.

Before traffic, always check Actuator readiness: [monitoring](../monitoring.md).

## Before / during / after

| Phase | What to do |
|-------|------------|
| Before | Readiness UP; known current writer; PITR archive on if you may need rollback; SQL (**15432**) separate from replication (**5615**) |
| During | Do not write a foreign/damaged `dataDir`; do not rotate the next URL host; do not disable `fsync` “so it passes” |
| After | New writer: `writerEligible` + client meta agree; replicas caught up; Multi-DC — one Active and current `regionEpoch` |

First metrics: `orchid_r`, `applyLagStale`, `repair_issued` / `repair_applied`, `rpoEstimateMs` — [monitoring](../monitoring.md).

## Symptom table

| Symptom | What to do | Details |
|---------|------------|---------|
| Node down / process dead | Do not write another process into the same `dataDir`. Restart the same directory or restore via PITR. Client: `rediscoverWriter()`, not the next host in the URL | [promote](ha-promote.md), [PITR](pitr.md) |
| `readiness` DOWN at start with replication | Wait for ORCHID sync; do not send load. Check `orchidSynced`, `reason`, `orchid_r` | [monitoring](../monitoring.md), [ORCHID](../../understand/orchid-consensus.md) |
| Write rejected (`OrchidNotSyncedException` / no admission) | Check peers, network, `R` threshold, disk/`fsync`. Do not disable fsync for TPS | [replication](../configuration/replication.md) |
| Disk full / OpLog cannot write | Free space; check `op-log` and archive. Archive I/O failure blocks truncate — that is protection | [durability](../configuration/durability.md), [PITR](pitr.md) |
| Client still writes the old writer after failover | Wait for `PROMOTE_NOTIFY` or call `rediscoverWriter()`. Do not round-robin hosts | [promote](ha-promote.md) |
| Reject on `regionEpoch` / dual-writer risk | One Active; Hold/Witness not in the write URL. Reconnect via rediscover | [multi-site](multi-dc.md) |
| Active site loss (`ASYNC_SHIP`) | RPO on Hold within lag is possible. New Active via claim; client rediscover | [multi-site](multi-dc.md) |
| Active site loss (`SYNC_VOTERS`) | Commits that passed quorum are already on voters. Same claim + rediscover; cost is WAN on every write | [multi-site](multi-dc.md) |
| Replica read rejected / `applyLagStale` | Do not “fix” in the client. Catch up first; `ha.max-stale-lag` | [replica reads](replica-reads.md) |
| `repair_issued` grows, `repair_applied` does not | HomologousRepair logs, disk, peer seq; no shared NFS `dataDir` | [monitoring](../monitoring.md), [replication state](../../understand/replication-state.md) |
| After restore “open TX disappeared” | Expected: dirty work before COMMIT is not in OpLog | [PITR](pitr.md) |

## Worked scenarios (signal → verify → escalate)

### A. Writer process dead, clients still connected

1. **Signal.** Writes fail or hang; readiness on the old host is DOWN or the process is gone.
2. **Verify.** No second process on the same `dataDir`. New writer: readiness UP and `writerEligible: true`. Client meta updated via `PROMOTE_NOTIFY` or `rediscoverWriter()` — not the next URL host.
3. **Escalate.** If two Actives or two writers appear — stop the extra process immediately; fix `regionEpoch` / claim ([multi-site](multi-dc.md)). If apps keep hitting the dead host — client bug (no rediscover).

### B. Disk full mid-write

1. **Signal.** OpLog / seal errors; readiness may go DOWN; archive may block truncate (protection).
2. **Verify.** Free space on the node disk; `op-log` and archive dirs writable; no shared NFS `dataDir`.
3. **Escalate.** If the journal looks torn after a crash — stop writes, restore on a **copy** via [PITR](pitr.md), then rejoin. Do not “repair” by writing into a live damaged directory.

### C. ORCHID will not admit writes

1. **Signal.** `OrchidNotSyncedException` / writes rejected while the process is up.
2. **Verify.** `orchidSynced`, `orchid_r` vs threshold; peers reachable; disk can write OpLog; no second writer.
3. **Escalate.** Long DOWN on a healthy network — check `R` and peer list; do not disable `fsync` “so it passes”.

### D. Replica reads break the application

1. **Signal.** Client does not see a just-written row; or `applyLagStale`.
2. **Verify.** Read-your-writes via replica is **not** guaranteed. Check `ha.max-stale-lag` and `readPreference`.
3. **Escalate.** If the product needs read-your-writes — read from the writer or wait for catch-up; do not “fix” by rotating hosts in the client.

### E. Multi-site: Active silent, Hold must claim

1. **Signal.** Active site quiet longer than `claim-timeout-ms`; writes stall or fail with region / epoch rejects.
2. **Verify.** Exactly one Hold (plus Witness if configured) gathers claim quorum; `regionEpoch` increments; new Active shows `writerEligible: true`. Witness never accepts DML.
3. **Escalate.** Clients call `rediscoverWriter()` — do not rotate the next host in the write URL. Two Actives → stop the extra process ([multi-site](multi-dc.md), [promote](ha-promote.md)).

## Writer loss (one site)

1. Ensure the old process is not writing the same `dataDir`.
2. Wait for or run promote; on the new writer — readiness UP and `writerEligible: true`.
3. Clients reconnect on protocol meta (`PROMOTE_NOTIFY` / `rediscoverWriter()`).
4. Replicas catch up; do not force reads from a lagging replica.
5. Record in the incident log: who became writer, replica lag, whether RPO occurred.

## Disk data loss

1. Stop the node; do not “repair” a live damaged directory by writing into it.
2. Restore from base + archive to the needed `--until-seq` ([PITR](pitr.md)).
3. Rejoin the ring; wait for catch-up.
4. Multi-site — only under Active fencing (`PitrActiveFence`).

## When to escalate

| Situation | Escalation |
|-----------|------------|
| Two processes write one `dataDir` or two Actives | Immediate: stop the extra writer, fix epoch; do not “fix” via URL |
| `repair_issued` grows for hours without `repair_applied` | Peer disk/network; if journal is doubtful — PITR drill on a copy |
| After failover clients still write the old host | Application bug (no rediscover) — fix the client, not the server |
| Need “rollback one hour” but archive was off | No archive data — only peers/sealed; enable archive before the next load |

## Do not

- Shared `dataDir` / NFS across the cluster.
- Send SQL to replication ports **5615** / **5616**.
- Disable `fsync` in operations for TPS.
- Treat HTTP `/replication/compare` as the writer pin source — lab lag compare, not operational HA discovery.

**Related:** [monitoring](../monitoring.md), [security](security.md), [upgrade](upgrade.md), [best practices](../../getting-started/best-practices.md).