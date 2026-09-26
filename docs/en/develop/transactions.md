# Transactions

Two sessions, one key. Until the first transaction commits, the second must not see its dirty rows. In Grid, until `COMMIT` changes live only in the session buffer; on `COMMIT` — with durability on — the journal comes first, then the map. Better a reject than a half-visible commit.

## The parts

| Part | Role |
|------|------|
| `Connection` | Transport: one TCP socket |
| `TxContext` | An independent transaction on that transport (plus a prepare handle) |
| `SqlTxBuffer` | The session's dirty mutations until `COMMIT` / `ROLLBACK` |
| `SqlRecordLockManager` | Fair record locks on `(table, key)` |
| `SqlTxCommitter` | OpLog markers and publication into the in-memory map |
| `MultiShardCommitBarrier` | Tracks the participating `table#shard` streams within one commit unit |

Important consequences of the model:

- An open transaction does **not** write to the in-memory map and does not touch ORCHID until `COMMIT`. Other sessions do not see its changes.
- Its own changes are visible inside the transaction: a `WHERE` over dirty rows is evaluated with the same filter conditions as over committed rows.
- **DDL inside an open transaction is rejected.** Run `CREATE TABLE`, `CREATE INDEX`, `DROP` and the rest in autocommit, before or after the transaction. This is a deliberate restriction: the catalog does not participate in rollback.
- With `grid.durability.enabled`, a commit does not proceed past a failure: if the journal or the quorum did not confirm the write, the data never becomes visible and the commit ends with an error.

## The client view

The canonical pattern is `Mono.usingWhen`: a resource (`begin()`), the work, and cleanup on any outcome.

```java
Mono<Void> transfer = Mono.usingWhen(
        connection.begin(),
        tx -> tx.createStatement("UPDATE accounts SET balance = balance - ? WHERE id = ?")
                .bind(0, 10L)
                .bind(1, 1L)
                .executeUpdate()
                .then(tx.createStatement("UPDATE accounts SET balance = balance + ? WHERE id = ?")
                        .bind(0, 10L)
                        .bind(1, 2L)
                        .executeUpdate())
                .then(tx.commit()),
        TxContext::rollback);
```

Why exactly this shape:

- `usingWhen` calls `rollback()` on an error in the body **and** on a cancelled subscription — forgetting the rollback is impossible.
- Nothing blocks. `close()`, `commit()` and `rollback()` return `Mono<Void>`; `.block()` inside library code and on a Netty / Reactor / virtual-thread carrier is forbidden — it stalls the event loop or pins the carrier.
- If you need a "do this whatever happens" side effect, add `doFinally` rather than a `try/finally` with a blocking call.

If the transaction reached `commit()`, a `rollback()` in the release phase does no harm: the session is closed, there is nothing to roll back.

The batch variant, when several statements share one transaction and one RTT:

```java
tx.executeBatch(List.of(
        "UPDATE accounts SET balance = balance - 10 WHERE id = 1",
        "UPDATE accounts SET balance = balance + 10 WHERE id = 2"));
```

Every statement in the batch sees the same dirty buffer; on an error the remainder is not executed.

## Parallel transactions

Several `connection.begin()` calls on one connection give several independent transactions. There are **two** caps — do not confuse them:

| Cap | Where | Default | Role |
|-----|--------|---------|------|
| Client `maxTxContexts` | URL / `ConnectionOptions` | **256** | Soft limit on concurrent logical sessions the client will open on one TCP |
| Server channel | SQL TCP listen (`SqlServer`) | **8** | Hard limit per accepted channel; Boot does **not** forward `grid.sql.max-tx-contexts` |

If the server answers `maxTxContexts=8 exhausted`, open another `Connection` — Boot does not raise the listen cap from `grid.sql.max-tx-contexts`. Details: [SQL server](../configure-and-operate/configuration/sql-server.md).

This is not a JDBC pool: do not open N connections to get N transactions. Isolation between them rests on record locks, `TX_*` markers in the journal and prepare handles, not on separate TCP channels.

## Savepoints

```java
Mono<Void> partial = Mono.usingWhen(
        connection.begin(),
        tx -> tx.createStatement("UPDATE t SET v = 1 WHERE id = 1").executeUpdate()
                .then(tx.savepoint("s1"))
                .flatMap(sp -> tx.createStatement("UPDATE t SET v = 2 WHERE id = 2").executeUpdate()
                        .onErrorResume(err -> tx.rollbackTo(sp).then(Mono.just(0L))))
                .then(tx.commit()),
        TxContext::rollback);
```

`savepoint` / `rollbackTo` / `release` work only inside an open transaction and only in memory — they never touch the journal or ORCHID. Sequences are not rewound by a rollback to a savepoint.

## Locks

Locks are taken per record key through `SqlRecordLockManager`: a fair queue on `(table, key)`. Waiting happens on a logic virtual thread, never on the Netty event loop.

`FOR UPDATE` and `SKIP LOCKED` execute **on the writer only** — a read replica rejects such a statement. With replication on, peer-lock agents are wired from **replication `peers`** (not `grid.sql.distributed-peers` — that knob is read-only SELECT/JOIN fan-out); the same indexed wire keys are locked on peers via `DistForUpdateCoordinator`. A Netty error on a peer lock is a **reject to the client**, not a silent local-only commit. Without agents, behaviour stays local-only. Multi-table / INNER JOIN `FOR UPDATE` locks are supported. Prepare/commit-dec peer votes are product-wired (2PC-lite for peer row locks) — not external XA. Details: [replica reads](../configure-and-operate/operations/replica-reads.md).

A typical queue pattern:

```sql
BEGIN;
SELECT id, payload FROM outbox WHERE status = 'NEW' ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED;
-- processing
UPDATE outbox SET status = 'DONE' WHERE id = ?;
COMMIT;
```

## PREPARE

`PREPARE name AS <sql>` / `EXECUTE name(...)` / `DEALLOCATE name` are parsed only by the ANTLR grammar `SimplifiedSql.g4`; the body is taken from the source text interval, so whitespace and case are preserved. On the client there is a wrapper `connection.prepare(name, bodySql)` → `PreparedHandle` with `bind(...)` / `execute()`.

`PREPARE` is a writer-session operation and is never routed to a replica.

## Multiple shards and multiple data centres

A commit unit is assembled from the participating `table#shard` streams: `MultiShardCommitBarrier` opens all streams, lets the operations through and closes them together, rolling back the already opened ones if something fails midway.

This is a **local barrier on the writer**, not full XA 2PC over foreign resource managers. Dist `FOR UPDATE` can take **peer row locks** and use prepare/commit-dec scaffolding (`DistForUpdatePrepareVotes`) when agents are wired — that is still not an external XA coordinator.

Cross-DC uses envelope membership (`TxEnvelopeCoordinator`): the set of streams in a transaction is written into the `TX_BEGIN` marker, and shipping to the remote site plus publication in the remote map happen only after every stream of the envelope has committed. In other words, half a transaction cannot become visible remotely.

The practical consequence: keep a transaction within one logical key set. The more shards in a single commit unit, the longer the barrier and the more expensive the rollback.

## What not to do

- Opening a transaction "just in case" and holding it across a network call to an external system — the record locks live exactly as long.
- Waiting on `FOR UPDATE` against a replica: you get a rejection, not a read.
- Treating `maxTxContexts` as a connection count, or opening a socket per transaction.
- Calling `.block()` on `commit()` / `rollback()` inside service code — only at the application boundary (`main`, CLI, test).

Next: [Java client](java-client.md), [write path](../understand/write-path-staging.md), [replica reads](../configure-and-operate/operations/replica-reads.md).
