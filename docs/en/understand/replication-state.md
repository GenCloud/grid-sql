# Replication state: memory vs disk

When replication is on (`grid.replication.enabled=true`), part of the cluster state always lives on disk. Which part matters: it decides what survives a restart, what has to be rebuilt, and what a peer can help you recover.

The short answer: **on disk — the mutation journal, the last confirmed commit number, and divergence-repair data. In memory — the working set of rows, the indexes, and the current view of live peers.**

## Layout

Each node writes into its own directory:

```
{data-dir}/{clusterId}/{nodeId}/
        ├── oplog/      mutation journal, by segment
        ├── orchid/     state.bin — last confirmed commit number
        ├── locus/      change-locus journal for repair
        └── overlay/    PIN marks, when persistence is enabled
```

*Figure 1. Node directory: everything needed to recover after a restart.*

The directory must be **local to the node**. One shared network volume for the whole cluster is not supported. Two processes pointed at one directory corrupt each other's segments, and the damage usually surfaces only at the next restart.

Sealed map files (`.gmap`, `.sbpt`, `.sbm`) live under the SQL catalog data tree; see [storage](storage-sealed-gmap.md).

### Path details

| Path | Content |
|------|---------|
| `oplog/{hexDomain}_{shard}.log` | Mutation payloads |
| `oplog/*.log.wpos` | Logical write length |
| `oplog/*.meta` | Truncate watermark |
| `orchid/state.bin` | 8-byte `lastCommittedSeq`, written after the OpLog confirm |
| `locus/{domain}_{shard}.locus` | Durable `VersionLocusMap` sidecar |
| `sealed/*.gmap` / `.sbpt` / `.sbm` | Sealed map plus secondary and bitmap indexes |
| `index-ckpt/*.meta` | A **keys watermark** (plus optional CRC) — rebuild eligibility, not a full in-memory tree dump |
| `overlay/*.ovl` | Durable overlay sidecar when enabled |

Legacy `map-wal/` and compact `snapshot/*.bin` map dumps have been **removed**: the durable path is the OpLog plus sealed files.

## Sequence counters

Four counters are easy to confuse, and each answers a different question.

| Counter | Storage | Meaning |
|---------|---------|---------|
| `orchid.lastCommittedSeq` | disk, `state.bin` | Global ORCHID commit counter |
| `OpLog.lastSeq(domain, shard)` | disk, oplog | Last mutation sequence for that shard; gaps across shards are normal |
| `appliedWatermark(domain, shard)` | memory | Last sequence applied to the local map |
| `peerAck(peerId, domain, shard)` | memory | Last `APPLY_ACK` received from that peer |

The first two survive a restart; the last two are rebuilt. That is why a restarted node re-derives how far each peer had caught up rather than trusting a stale view.

## What only lives in memory

- `GridScalableMap` — the working set of hot keys, rebuilt from sealed files and the journal tail.
- In-memory indexes (B+ tree, bitmap) — accelerators. Their durable form after sealing is `.sbpt` and `.sbm`.
- The live-peer view and current phases — operational state, reassembled after a restart.
- Changes still sitting in the write queue.

None of this is the only copy of data when durability is enabled. Losing the heap loses speed, not rows.

## Hydration after a restart

The sequence is: sealed map files (plus `.sbpt` / `.sbm`) → replay the OpLog from `watermark + 1` → rebuild indexes, or hydrate from the index-checkpoint keys watermark. The in-memory B+ tree is an accelerator; the durable secondary index is the sealed `.sbpt`.

What does **not** come back is uncommitted state: a transaction that never reached `COMMIT` was never in the journal, so after a restart it is simply gone. That is expected, not data loss.

## Fail-closed ordering

ORCHID commit happens **before** the map put. The OpLog append is followed by `confirmPersisted` — by `MutationRecorder` on the writer and by `ReplicaApplier` on a peer. The ship path blocks on a flow permit rather than queueing without bound.

No confirmation means no visible row. There is no path that makes a rejected write appear later.

## Transaction markers versus staging

Three distinct things that are worth keeping separate:

| Thing | How it is stored |
|-------|------------------|
| Transaction lifecycle | `TX_BEGIN`, `TX_COMMIT`, `TX_ABORT` — OpLog entries that passed ORCHID (`recordTxMarker`) |
| Row mutations | The full chain through `MutationRecorder`: ORCHID → OpLog → confirm → map |
| Apply-time staging | `ReplicaApplier` holds the operations of an open transaction until `TX_COMMIT`, then applies them as a unit |

Markers do not replace row operations; neither works without the other. Step-by-step: [the write path](write-path-staging.md).

## Solo writes, live peers and voters

The rules that protect against divergence during a partition:

- A solo write is allowed **only** when the configured peer list is empty (`peerIds.isEmpty()`, bootstrap with `N = 1`).
- With `N ≥ 2` configured and `livePeerCount() == 0` after a partition, the node is **not** considered synced. An empty live view does not grant the right to write.
- `forgetPeer` clears the live view but **does not shrink** the configured quorum.
- The proposer is phase-ranked: `min(nodeId)` among self and seen peers.
- A peer is promoted back to voter once its `APPLY_ACK` has caught up; a HELLO with the same `clusterId` adds it back via `addPeer`.

## Repair and placement readiness

| Component | Defaults | Contract |
|-----------|----------|----------|
| `HomologousRepair` | `homologous-enabled: true`, `reconcile-interval-ms: 5000` | Locus reconcile, range reship, single-row fetch |
| `AdaptiveReplicaSwarm` | `enabled: true`, `score-window-ms: 5000`, `migrate-threshold: 0.3` | Hints only, until a cutover is executed by `ShardMigrator` |
| Ownership cutover | — | QUIESCE → CATCH_UP → ownership change; the journal ships before ownership moves |

Details: [shard placement](overlay-and-swarm.md).

## What to check on divergence

| Symptom | Where to look |
|---------|----------------|
| `repair_issued` grows, `repair_applied` does not | `HomologousRepair`, disk, sequence watermarks — [monitoring](../configure-and-operate/monitoring.md) |
| Different ORCHID sequence on peers after catch-up | Locus and OpLog; confirm there is no shared `dataDir` |
| Fresh commits missing after a restart | Compare the sealed watermark against the OpLog tail; check archive coverage |
| Readiness DOWN after start | The node is not ORCHID-synced yet (`orchidSynced`) |
| Uncommitted transaction gone after restart | Expected: dirty state before `COMMIT` is not in the journal |
| Two writers after a site hand-off | Check `regionEpoch` and the client pin — [promote a node](../configure-and-operate/operations/ha-promote.md) |
| Client still on the old writer | Not a state problem — [promote a node](../configure-and-operate/operations/ha-promote.md) |

## Out of scope

There is no CRDT-style merge of competing versions, no biological naming in the public API or YAML, no `hdcrm.*` configuration namespace, and WAN phase coupling is not the default multi-site consistency path. Where the metaphors stop and the implementation starts: [claim boundaries](bio-inspired.md).

## Related

[replication network](replication-network.md), [ORCHID](orchid-consensus.md), [storage](storage-sealed-gmap.md), [backup and restore](../configure-and-operate/operations/backup-restore.md), [durability](../configure-and-operate/configuration/durability.md).
