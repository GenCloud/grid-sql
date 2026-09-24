# The write path

A write in Grid does not land in the map directly. Several layers sit between the client and a visible row, and each solves its own problem: asynchronous flushing, agreement between nodes, the on-disk journal, atomic transaction application.

Two orderings on this path are contracts rather than implementation details, and the rest of the page is mostly about them.

## Overall ordering

```
INSERT / UPDATE / DELETE
        │
   SqlEngine → TableStore
        │
   write queue (GridEntriesProcessor)
        │
   ┌────┴──────────────────────────────┐
   │ is durability enabled?            │
   └────┬──────────────────────────┬───┘
        │ yes                      │ no
        ▼                          ▼
  MutationRecorder            put into the map
   ORCHID → OpLog → confirmation
        │
        ▼
   put into the map  ──►  index queue
        │
        ▼
   ship to peers over Netty
```

*Figure 1. Without durability the path is short; with durability, agreement and the journal come first.*

**Contract one: agreement and journal first, visibility second.** On failure we do not proceed. If ORCHID refused admission or the journal did not confirm the write, the row never appears in the map and the client gets an error. There is no intermediate "in the map but not on disk" state.

**Contract two: the map first, the index queue second.** A change becomes visible in `GridScalableMap` and is only then handed to the index worker. Do not reorder that without a dedicated task — readers that resolve a key through an index and then fetch the row rely on the row being there already.

## Inside the write queue

The staged pipeline is short but worth spelling out, because queue depth is one of the few write-path numbers an operator can act on:

1. `GridEntriesProcessor.add` accepts the change and places it into staging for its shard. The calling SQL thread is released here — it does not wait for disk.
2. `GridEntriesWorker` drains staging. With durability enabled, this is where `MutationRecorder` runs the ORCHID → OpLog → confirmation chain.
3. The confirmed change is put into the map, which is the moment the row becomes visible to other sessions.
4. `GridIndexWorker` picks the change up and updates secondary indexes.

Reads consult staging before the map, so a session never fails to see a change that its own commit has already accepted. Entries sitting in staging are also protected from working-set eviction: eviction must never be able to drop a change that has not reached the journal.

When queue depth grows and stays high, the bottleneck is downstream — ORCHID admission or journal fsync — not the queue itself. Adding client threads at that point raises latency without raising throughput.

## Three write-buffering layers

| Layer | Where it lives | What it does |
|-------|----------------|--------------|
| Write queue | `GridEntriesProcessor` and `GridEntriesWorker` | Asynchronously flushes changes into the map. With durability enabled, `MutationRecorder` first runs the ORCHID → OpLog → confirmation chain |
| Apply-time buffering | `ReplicaApplier`, per `(domain, shard)` pair | Buffers `UPSERT` and `DELETE` between `TX_BEGIN` and `TX_COMMIT`, flushes on commit, discards on rollback and on an unclosed transaction |
| Cross-DC envelope | `TxEnvelopeCoordinator` | Holds a multi-shard transaction until every shard has committed |

The difference is fundamental. The first layer is about local write throughput. The second is about making sure a reader on a replica never sees half a transaction. The third is about a remote data centre receiving a transaction in full.

Do not confuse the **write queue** with the **apply buffer on a peer**:

| | Write queue (`GridEntriesProcessor`) | Apply buffer (`ReplicaApplier`) |
|--|--------------------------------------|----------------------------------|
| Where | The node that accepts the client write | The peer that applies someone else's journal |
| When the row is visible | After ORCHID → OpLog → put into the map (with durability) | After `TX_COMMIT` on the whole buffered block |
| On failure | The client gets an error; the map does not change | The block is discarded; there is no partial visibility |

The queue is about accepting a write here quickly and safely. The apply buffer is about showing a whole transaction on a replica.

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

This is a **local commit-flush barrier on the proposer**, not distributed two-phase commit over foreign resource managers and not a separate arbiter service.

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

## Related

[storage](storage-sealed-gmap.md), [architecture overview](architecture-overview.md), [durability](../configure-and-operate/configuration/durability.md), [DML](../sql/dml.md), [failures](../configure-and-operate/operations/failures.md).
