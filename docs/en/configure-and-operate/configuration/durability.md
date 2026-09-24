# Durability

A node keeps data on disk through the mutation journal (OpLog) and sealed map files (`.gmap`, `.sbpt`, `.sbm`). That path needs no peers: `grid.durability.enabled` is independent of `grid.replication.enabled`, and a solo node can be fully durable with an empty peer list.

With durability enabled a commit is appended to the journal and acknowledged before the row becomes visible in the in-memory map. If the journal write fails, the commit is aborted instead of partially applied. Memory holds a working set, not the only copy of the data.

Peer shipping, quorum, and catch-up are a separate layer — see [replication](replication.md).

## Reference profile

```yaml
grid:
  durability:
    enabled: true
    hydrate-mode: LAZY          # FULL | LAZY
    working-set-max-entries: 262144
    adaptive-disk-first: true
    oplog-archive:
      enabled: false            # required for PITR
      dir: ./data/oplog-archive
  replication:
    op-log:
      data-dir: ./data-primary/replication
      fsync: true
      segment-size: 64
```

The journal keys live under `grid.replication.op-log.*` even when replication is off: the OpLog is shared by the local durable path and by peer shipping.

Starter profiles `primary`, `replica`, and `capacity` already set `fsync: true`, `hydrate-mode: LAZY`, and a bounded working set.

## Settings

| Setting | Default | Effect |
|---------|---------|--------|
| `grid.durability.enabled` | `true` | Commit passes ORCHID and the OpLog before becoming visible in memory; a journal failure aborts the commit |
| `grid.durability.hydrate-mode` | `FULL` | `FULL` loads sealed files and the journal into memory at startup; `LAZY` maps sealed files and replays only the journal tail |
| `grid.durability.working-set-max-entries` | `0` (unbounded) | Working-set ceiling. Above `0`, cold committed keys are evicted by LRU; dirty and staged entries are never evicted |
| `grid.durability.adaptive-disk-first` | `true` | Under disk pressure, lean harder on the sealed-miss load path instead of growing memory |
| `grid.durability.oplog-archive.enabled` | `false` | Copy the journal range to the archive before truncate; a copy failure cancels the truncate |
| `grid.durability.oplog-archive.dir` | `./data/oplog-archive` | Archive root, consumed by [PITR](../operations/pitr.md) |
| `grid.durability.oplog-archive.stream-enabled` | `false` | Append-only journal stream off the node on safe truncate |
| `grid.durability.oplog-archive.stream-dir` | `""` | Stream root; blank resolves to `{dir}/stream` |
| `grid.replication.op-log.data-dir` | `./data/replication` | Journal segment directory, one per node |
| `grid.replication.op-log.fsync` | `true` | `FileChannel.force` on append. `false` only isolates a bottleneck on a test host |
| `grid.replication.op-log.segment-size` | `1024` | Segment size in MiB; drives rotation and truncate granularity |

`hydrate-mode` defaults to `FULL`, which is the wrong choice for most production corpora. Set `LAZY` explicitly unless the sealed set fits comfortably in heap.

## Choosing a hydrate mode

| Mode | Startup | Memory after start | Key miss |
|------|---------|--------------------|----------|
| `FULL` | Longer: sealed files plus journal are read into memory | Working set warm immediately | Rare — keys already resident |
| `LAZY` | Faster: sealed files are mapped, only the journal tail is replayed | Grows with traffic | Loaded from sealed on miss |

Choose `FULL` when the sealed corpus is small and the first requests after a restart must not pay a disk miss. Choose `LAZY` when the corpus is large relative to heap, or when a long startup is worse than a warm-up period — this is the usual production setting.

## Sizing the working set

1. Start with `working-set-max-entries` at roughly the number of rows your hot queries touch in a peak hour.
2. Watch the sealed miss counters (`grid.sealed.miss`, `grid.replication.sealed_misses`) and `grid.replication.map_hit_rate` — see [monitoring](../monitoring.md).
3. If miss rate stays high while heap has headroom, raise the ceiling. If heap pressure or GC pauses grow, lower it and keep `adaptive-disk-first: true`.
4. Leave the value at `0` only when the whole corpus is meant to stay resident and heap is sized for it.

Eviction never touches uncommitted work: dirty transaction buffers and entries still in the staging queue stay in memory until they commit.

## On-disk layout

| Artifact | Role |
|----------|------|
| OpLog segments | Mutation journal; truncated only through the seal watermark |
| sealed `.gmap` | Row payloads on disk |
| `.sbpt` / `.sbm` | Sealed secondary and bitmap indexes |
| orchid `state.bin` | Last acknowledged sequence number |
| `locus/` | HomologousRepair state |
| `index-ckpt/` | Index checkpoint catalog |
| `oplog-archive/` | Optional archive used to recover to a chosen sequence |

Read-path and mapping details: [GMAP storage](../../understand/storage-sealed-gmap.md).

## Seal, truncate, archive

1. Commits accumulate in the OpLog, with `force` on append when `fsync: true`.
2. Seal compacts the journal tail into sealed `.gmap` and `.sbpt` files and raises the watermark.
3. Truncate cuts the journal only through that watermark. With `oplog-archive.enabled: true` the range is copied to the archive first; an archive I/O error cancels the truncate rather than dropping the range.
4. After a restart, the authoritative state is sealed files plus the journal tail — and the archive, for point-in-time recovery.

A solo durable node (`grid.replication.enabled: false`) follows exactly this order without peers.

## Verifying durability after a restart

Run this on a staging node before you rely on the configuration:

1. Insert a known row and commit; note the primary key.
2. Kill the process without a shutdown hook (`SIGKILL` / `Stop-Process -Force`). This leaves a torn journal tail, which is the case you want to exercise.
3. Restart on the same `dataDir`. The server truncates the torn tail and replays intact records.
4. Read the row by primary key. It must be present.
5. Run a non-primary-key `SELECT` on the same table. Index discovery hydrates the shards and indexes the journal tail as a delta batch, so secondary lookups must return the row too (covered by `LazySelectAfterRestartTest`).
6. Confirm Actuator readiness is UP before sending application traffic — [monitoring](../monitoring.md).

Under `LAZY`, low memory usage right after step 3 is expected and is not data loss.

## Symptom and action

| Symptom | Cause | Action |
|---------|-------|--------|
| Fresh commits missing after a crash | `fsync: false` | Set `grid.replication.op-log.fsync: true`; discard throughput figures collected without fsync |
| Corrupt journal or sealed segments on two nodes | Shared `dataDir` on NFS/SAN | One local directory per node; never share a data directory between processes |
| Slow startup or out-of-memory on start | `hydrate-mode: FULL` on a large sealed corpus | Switch to `LAZY` and bound `working-set-max-entries` |
| Cold keys slower than usual after restart | Normal `LAZY` behaviour | None; keys load from sealed on miss and stay in the working set |
| Non-primary-key `SELECT` empty while primary key works | Stale build before index discovery | Upgrade; verify with the restart procedure above |
| Nothing to restore after data loss | `oplog-archive` was disabled | Enable the archive in advance — [PITR](../operations/pitr.md) |
| Truncate stops and the journal keeps growing | Archive target unwritable or full | Fix archive I/O; the blocked truncate is deliberate protection |

## Constraints

- `dataDir` and `op-log.data-dir` are per node. A single share for the whole cluster is not supported.
- The PITR archive must be enabled before the incident; it cannot be produced retroactively.
- With durability enabled, memory is a working set. Sizing memory as if it were the only copy will mislead capacity planning.

**Related:** [replication](replication.md), [PITR](../operations/pitr.md), [backup and restore](../operations/backup-restore.md), [write path](../../understand/write-path-staging.md), [capacity](../../performance/capacity-slo.md).
