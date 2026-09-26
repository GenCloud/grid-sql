# Storage: sealed map files

Durability is on and the node restarts. Is an “empty” heap a disaster or normal? With `hydrate-mode: LAZY` it is normal: truth is on disk in the journal (OpLog) and sealed files; memory holds only the hot set.

Ordering is fixed: journal first, then seal a group of changes into files, and only then truncate the journal. The in-memory map is an accelerator, not storage. Settings: [durability](../configure-and-operate/configuration/durability.md).

## How data is divided

A key belongs to exactly one shard of its table: `shard = fastHash(key) % shards`. The shard is the unit of almost everything else — journal streams are per `(domain, shard)`, sealed files are per shard and node, commit units are marked on `table#shard`, and placement moves ownership one shard at a time.

Inside a shard, a row is a packed binary record with an offset table at the end. Nothing in the storage layer knows about columns as Java fields; it knows offsets, lengths and the catalog that maps a column name to one of them.

## On-disk artefacts

| Artefact | Role |
|----------|------|
| OpLog | The mutation journal. A commit counts as done only after the journal write; after sealing, the journal is truncated through a watermark |
| `{domain}_{shard}_n{node}.gmap` | An immutable node file: directory (buckets), key → offset mapping and the payloads themselves |
| `*.sbpt` | A sealed secondary B+ tree with a fixed page size. New files are **VERSION 2** (signed INT/LONG compare); **VERSION 1** is rejected on read — reseal / `dumpDomain` required |
| `*.sbm` | A sealed bitmap index |
| `index-ckpt/*.bytes` | A key watermark plus CRC. This is **not** a full dump of the in-memory index: after sealing, secondary indexes live in `.sbpt` |
| `orchid/state.bin` | The global sequence number of the last confirmed commit |
| `locus/` | Companion data for divergence repair (`HomologousRepair`) |

Snapshot markers store only OpLog ranges for hydration; there is no map dump in them.

The obsolete mechanisms — WAL on top of the map and the double-write of compact snapshots — have been removed. If you are looking for a `map-wal/` directory or a compact `snapshot/*.bin` file because an older note mentioned them, they no longer exist.

## Inside a `.gmap` node file

A node file is written once and never modified. It contains three regions:

1. **Directory** — a bucket array that maps a key hash to a slot chain. This is what makes a point read a hash lookup rather than a scan.
2. **Key → offset table** — for a matched slot, the exact position and length of the payload.
3. **Payloads** — the packed row bytes, laid out contiguously.

Because the file is immutable, a later change to the same key does not edit it. The new version goes through the journal and lands in a later sealed generation; the read path resolves the freshest version first. Space from superseded versions is reclaimed when a shard is re-sealed, not in place.

## Reads

```
query
  │
  ├─► write queue (changes not yet flushed)
  │
  ├─► in-memory map (working set)
  │
  ├─► sealed .sbpt index
  │
  └─► node file .gmap  (memory miss → load the key)
```

*Figure 1. Row lookup order: from the freshest to the coldest.*

### Working-set miss

When the key is not in `GridScalableMap`, the read does not stop and does not start a full partition scan:

1. First the sealed secondary index (`.sbpt` / `.sbm`) is consulted, if the query uses an index.
2. On an index hit or a primary-key read, the needed **mmap window** in the `.gmap` node file is opened and the payload is read by offset.
3. The loaded key may return to the working set; the next access stays off disk until eviction.

**An index miss does not become a full scan.** If the sealed index did not find the key, the answer is empty (`sealedPartitionScan = 0`); no sweep over all partitions follows. If you ever observe that metric climbing, treat it as a defect rather than as a tuning problem.

To dump domain contents for diagnostics: `SealedGridMapService.dumpDomain`.

## How files are mapped into memory

The default is a **windowed mmap**: a window of `WINDOW_BYTES` is mapped, not the whole file. A permanent mapping of all payloads is used only when they fit entirely into one window. The former whole-file threshold no longer exists, so a large sealed corpus does not translate into a large permanent address-space commitment.

A node file's directory is mapped lazily as well: `openShard` keeps the paths until the first miss for that node, so opening a shard with many node generations is cheap until something is actually read from them.

The lock lifecycle is designed not to hold up SQL or the network:

- `readLock` — only around fetching a value and mapping a window;
- `writeLock` — only around close and unmap.

Neither lock may be held while SQL executes, while waiting for quorum, or while waiting on a Netty network thread. That constraint is what keeps a slow disk from turning into a stalled event loop.

## Seal and truncate

1. Mutations land in the OpLog first; nothing is visible before the journal confirms.
2. A background seal packs a key range into node files `sealed/{domain}_{shard}_n{node}.gmap` (plus `.sbpt` / `.sbm` when those indexes exist) and raises the watermark.
3. After a successful seal the journal may truncate **through the watermark** — so the live OpLog tail stays short. With the PITR archive enabled, the range is copied to the archive first, and a copy failure cancels the truncate.
4. A working-set miss now reads sealed files; a secondary-index miss goes to `.sbpt`.

Growth under `sealed/` after load and a shorter OpLog after seal are the normal cycle, not a "lost journal".

## Cold start

Two hydration modes are selected by the `hydrate-mode` parameter:

| Mode | What happens | Cost |
|------|--------------|------|
| `FULL` | Sealed files and the journal tail are preloaded into memory, indexes are rebuilt | Slower start, more RAM, warm working set immediately |
| `LAZY` | Sealed files are mapped into memory, only the journal tail is read; a miss loads the required keys | Fast start, RAM grows with traffic, first touch of a cold key hits disk |

The general sequence: sealed files (`.gmap` plus `.sbpt` / `.sbm`) → replay of the OpLog tail → index rebuild. Or, if an index checkpoint exists, hydration from its key watermark.

`SnapshotService` works only with OpLog ranges. An index checkpoint does **not** restore the in-memory index in full — it only says how far the keys have already been sealed.

`LAZY` is the usual production choice: a large sealed corpus with `FULL` means a long start and a real OOM risk. Choose `FULL` when the sealed set is small and you want predictable latency from the first request after a restart.

## Working set and eviction

The `working-set-max-entries` parameter (any value above zero) enables LRU eviction for cold committed keys. Dirty rows and anything still sitting in the write queue are never evicted — eviction must never be able to drop a change that has not reached the journal. An evicted key is loaded back from the sealed files on the next access.

Sizing it is a memory-versus-miss trade-off:

- Too high, and the heap carries cold keys that nobody reads.
- Too low, and a working set that would have fit starts paying a disk miss on every access.
- The honest signal is the working-set hit rate together with read latency, not the entry count on its own.

## Disk growth and seal pressure

| Observation | Meaning | What to do |
|-------------|---------|------------|
| `sealed/` grows after sustained write | Normal seal packing | Size disk for sealed plus OpLog plus archive; growth alone is not corruption |
| OpLog stays large and never shortens | Seal or truncate is stuck; archive I/O may be blocking truncate | Check durability config, free space, archive errors — [durability](../configure-and-operate/configuration/durability.md) |
| Disk near full | Writes, seal and archive can all fail | Free space before continuing; if the journal looks torn, stop writes and restore on a copy |
| Index miss starts a full partition scan | Should not happen with sealed `.sbpt` | Treat as a defect; check the `sealedPartitionScan` metric |

Plan capacity for three things at once: the sealed corpus, the live OpLog tail, and — if PITR is enabled — the archive. The archive is the one that grows without an upper bound unless it is rotated.

## When to reach for recovery

Before a host migration, when disk health is doubtful, or after a crash with a torn OpLog tail: take or restore a sealed **base** and confirm archive coverage past the watermark. Day-to-day seal cycles do not require a base on every restart.

Procedures: [backup and restore](../configure-and-operate/operations/backup-restore.md), [point-in-time recovery](../configure-and-operate/operations/pitr.md).

Next: [write path](write-path-staging.md), [durability](../configure-and-operate/configuration/durability.md), [indexes](../sql/indexes.md).
