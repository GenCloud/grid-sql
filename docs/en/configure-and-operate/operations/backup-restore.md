# Backup and restore

How to protect a durable Grid node and bring it back. Point-in-time detail and archive knobs: [PITR](pitr.md). On-disk layout: [durability](../configuration/durability.md).

## What to back up

Per node, on **local** `dataDir` (never a shared NFS/SAN volume for the whole cluster):

| Artifact | Role |
|----------|------|
| Sealed map files (`.gmap`) | Compacted key → payload store |
| Sealed indexes (`.sbpt` / `.sbm`) | Secondary indexes on disk |
| OpLog (active tail + archived segments) | Ordered mutations; required for fresh commits |
| Node config | Profiles, peers, ports, `cluster-id` — restore must match topology intent |
| Auth catalog (`privileges.meta` under catalog root) | Users and grants — include with the same backup policy as `dataDir` |

Typical layout under the node replication / durability root (names from `SealedBaseBackupUtil`):

```text
<dataDir>/
  sealed/          # .gmap payloads
  orchid/          # ORCHID durable state
  locus/           # homologous locus
  index-ckpt/      # index checkpoint
  … OpLog segments + optional oplog-archive (separate dir)
```

## Base backup API

For a PITR-ready base at watermark `W` (offline / ops JVM, not Netty EL):

```text
SealedBaseBackupUtil.backupBase(dataDir, backupDir, W)
# later install into an empty dataDir:
SealedBaseBackupUtil.clearDataDir(dataDir)
SealedBaseBackupUtil.installBase(backupDir, dataDir)
```

Prefer a quiet window or clean stop so sealed + orchid state match. Full restore-to-seq is [PITR](pitr.md) (`PitrRestoreMain`).

## Seal and why it matters

Sealing moves accumulated journal entries into sealed files and allows a safe OpLog truncate after a watermark. Before a production workload you may need to rewind:

1. Enable the journal archive (`oplog-archive` / PITR) **before** that workload — [PITR](pitr.md).
2. Take a base at a known watermark on a schedule or before risky work (disk move, major layout change).
3. Do not call a RAM-only snapshot or an unsealed OpLog tail a complete backup.

## Backup practice

1. Prefer a quiet window or a node that is not the sole writer.
2. Copy `dataDir` consistently: filesystem snapshot or cold copy after a clean stop. Do not copy half-written segments.
3. Store copies off-box; label `node-id`, time, and binary version.
4. Practise restore on a spare host at least once — before an incident.

## Restore order (cold)

1. Stop the Grid process for that node.
2. Replace `dataDir` with the backup (sealed + OpLog + archive as taken).
3. Restore matching configuration (peers, ports, durability flags, same `cluster-id`).
4. Start the node; wait for hydrate and — if replication is on — ORCHID sync and replica catch-up.
5. Confirm Actuator readiness before traffic ([monitoring](../monitoring.md)).
6. If this node must become writer, follow [promote](ha-promote.md). Do not invent a second writer.

## After restore — verification matrix

| Check | Pass criteria |
|-------|----------------|
| Process | Node process up; no second process on the same `dataDir` |
| Readiness | Actuator readiness UP; with replication — `orchidSynced` / `orchid_r` above admission |
| Writer role | At most one `writerEligible: true` in the write ring; promote only if this node must write ([promote](ha-promote.md)) |
| Application path | One write + one read through the real client URL (not HTTP `/replication/compare`) |
| Peers | Replicas catching up; do not force reads while `applyLagStale` |
| Multi-site | Matching `regionEpoch` / Active fencing if PITR touched Active |

Until every row that applies to your topology passes, do not call the restore successful.

## Multi-node notes

- Restore **one node at a time**. Keep a healthy writer while a replica rebuilds when possible.
- After restore, lagging peers catch up via OpLog / repair — [failures](failures.md), [replication state](../../understand/replication-state.md).
- Multi-site: respect Active/Hold and `regionEpoch` — [multi-site](multi-dc.md). Active is fenced for PITR (`PitrActiveFence`).

## Typical failures

| Symptom | Check |
|---------|--------|
| Node starts “empty” under LAZY | Normal: truth is sealed + OpLog tail; not the same as a lost `dataDir` |
| Peers refuse the node | Wrong `cluster-id` / epoch; shared `dataDir` across two processes |
| Open TX “gone” after restore | Expected: dirty buffer is not in OpLog until COMMIT |
| Archive empty but you need “one hour ago” | No data — enable archive **before** the load |

## What not to do

- Do not treat RAM-only state as a backup.
- Do not share one `dataDir` between two processes.
- Do not keep writing into a live damaged directory — copy first, then PITR.
- Do not admit traffic before readiness is UP.

## Related

- [PITR](pitr.md)
- [Upgrade](upgrade.md)
- [Production checklist](../../getting-started/production-checklist.md)