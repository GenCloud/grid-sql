# The write path

The client said `UPSERT`. When do other sessions see the row? Not as soon as the SQL thread returns: agreement and the on-disk journal come first, then the in-memory map. Better a client error than “already in the map, not yet on disk”.

Two orderings on this path are contracts. The rest of the page is the mechanics around them.

## Journal first, visibility second

```
INSERT / UPDATE / DELETE
        │
   SqlEngine → TableStore
        │
   write queue
        │
   ┌────┴──────────────────────────────┐
   │ is durability enabled?            │
   └────┬──────────────────────────┬───┘
        │ yes                      │ no
        ▼                          ▼
  ORCHID → OpLog → confirmation    put into the map
        │
        ▼
   put into the map  ──►  index queue
        │
        ▼
   ship to peers
```

*Figure 1. Without durability the path is short; with durability, agreement and the journal come first.*

**Contract one: agreement and journal before visibility.** If ORCHID refused or the journal did not confirm, the row never appears in the map. There is no “in the map but not on disk” state.

**Contract two: the map before the index queue.** A reader that resolves a key through an index and then fetches the row relies on the row already being there. Do not reorder that without a dedicated task.

## What the write queue does

Queue depth is one of the few write-path numbers an operator can act on:

1. The change lands in shard staging. The SQL thread is free here — it does not wait for disk.
2. With durability on, the drain runs ORCHID → OpLog → confirmation.
3. The confirmed change is put into the map — that is when other sessions see the row.
4. A separate worker updates secondary indexes.

Reads consult staging before the map, so a session never loses a commit it already accepted. Staging entries are not working-set-evicted: eviction must never drop a change that has not reached the journal.

When queue depth grows and stays high, the bottleneck is downstream — ORCHID admission or journal fsync. Adding client threads then raises latency without raising throughput.

## Three write-buffering layers

| Layer | Where it lives | What it does |
|-------|----------------|--------------|
| Write queue | Local drain into the map | Accepts changes asynchronously. With durability on, ORCHID → OpLog → confirmation runs before put |
| Apply-time buffering | The node applying someone else's journal | Buffers `UPSERT`/`DELETE` between `TX_BEGIN` and `TX_COMMIT`; flushes on commit, discards on rollback |
| Cross-site envelope | Several shards of one TX | Holds the transaction until every shard has committed |

The first layer is local write throughput. The second keeps a replica reader from seeing half a transaction. The third delivers a full transaction to a remote site.

Do not confuse the **write queue** with the **apply buffer on a peer**:

| | Write queue | Apply buffer |
|--|-------------|--------------|
| Where | The node that accepts the client write | The peer that applies someone else's journal |
| When the row is visible | After ORCHID → OpLog → put into the map (with durability) | After `TX_COMMIT` on the whole buffered block |
| On failure | The client gets an error; the map does not change | The block is discarded; there is no partial visibility |

The queue is “accept a write here quickly and safely”. The apply buffer is “show a whole transaction on a replica”.

## Transactions

While a transaction is open, its changes sit "dirty" in the session's `SqlTxBuffer`: they are neither in the map nor in ORCHID nor in the journal. At `COMMIT`, `SqlTxCommitter` assembles the operation block and submits it as one unit on `table#shard`.

With replication enabled, lifecycle markers are additionally written into the journal:

1. `TxLifecycleListener` calls `ReplicationCoordinator.recordTxMarker` and puts `TX_BEGIN`, `TX_COMMIT` or `TX_ABORT` into the OpLog — through ORCHID, by the same path.
2. The row changes themselves still go through the full ORCHID → OpLog → confirmation → map chain. Markers **do not replace** the row operations.
3. On a peer, `ReplicaApplier` applies the buffered block atomically. A reader never sees a partially applied transaction.

Two restrictions follow from the model and surprise people often enough to repeat here. **DDL inside an open transaction is rejected** — the catalog does not participate in rollback. And an uncommitted transaction leaves nothing behind after a crash: dirty state was never in the journal, so there is nothing to replay.

Record locks for the duration of a transaction are taken by `SqlRecordLockManager`: a fair lock on the `(table, key bytes)` pair. Waiting for a lock always happens on a logic virtual thread and never on a Netty network thread.

Visibility rules, lock scope and what `PREPARE` does to a session: [visibility and concurrency](concurrency-and-visibility.md). Client API: [transactions](../develop/transactions.md).

## Multi-shard transactions

`MultiShardCommitBarrier` collects the participating `table#shard` streams and waits until a single contiguous block lands in the journal: `TX_BEGIN` → operations → `TX_COMMIT`. If one stream fails midway, the already opened ones are rolled back together.

This is a **local commit-flush barrier on the writer**, not distributed two-phase commit over foreign resource managers and not a separate arbiter service.

The practical consequence for schema design: keep a transaction inside one logical key set. The more shards in one commit unit, the longer the barrier holds and the more expensive a rollback becomes.

## What happens to indexes

Once a change has reached the map, it is handed to `GridIndexWorker`. In-memory indexes are B+ trees of key pointers (`IndexPointerRef`), while the key values themselves are read from the packed row through `LogicalFieldCursor`. The row is never decoded into objects along the way: the hot path works on bytes.

A structural `UPDATE` also stays in bytes. `LogicalFieldCursor.rewrite` and `BlobFieldModifier` assemble the new row, and the final `UPSERT` — the complete row, not a delta — goes into the journal. That is why `SET total = total + 10` is not a storage-level increment: the server reads the current value under a record lock, recomputes it, and writes the whole row.

Index declaration and kinds: [indexes](../sql/indexes.md).

## When durability is off

Without `grid.durability.enabled` the chain is short: queue → map → index. Writes are faster, and nothing survives a process restart. This mode is useful for caches whose content can be rebuilt from an upstream source, and for isolating a bottleneck in the lab.

It is not a "faster durable mode". Numbers measured with durability off — or with `fsync: false` — belong to a different contract and must not be compared against durable thresholds.

## Typical mistakes

| Mistake | Why it is wrong |
|---------|-----------------|
| Expect a row visible right after `UPSERT` without a TX commit | Until `COMMIT`, changes live only in `SqlTxBuffer` |
| Assume idle CPU means more client threads will raise write TPS | The write ceiling is ORCHID admission and journal fsync, not CPU |
| Run DDL inside an open transaction | Rejected: the catalog is not transactional |
| Point the SQL client at the replication port (5615) | That is not SQL TCP; the frame is rejected as malformed |
| Compare `fsync: false` throughput with `fsync: true` | Different durability contracts |
| Expect a dirty transaction to survive a crash | Dirty state never reached the journal |

## Where to look when writes are slow

- Write admission rejections (`OrchidNotSyncedException`) mean the issue is node synchrony: [ORCHID](orchid-consensus.md).
- A high, stable queue depth with idle CPU points at fsync or admission, not at the queue.
- Growing apply lag on a replica is the expected asynchrony, see [replication network](replication-network.md).
- Lock waits that scale with concurrency point at hot keys rather than at the write path: [visibility and concurrency](concurrency-and-visibility.md).
- Metrics and health: [monitoring](../configure-and-operate/monitoring.md).

Next: [storage](storage-sealed-gmap.md), [ORCHID](orchid-consensus.md), [durability](../configure-and-operate/configuration/durability.md).
