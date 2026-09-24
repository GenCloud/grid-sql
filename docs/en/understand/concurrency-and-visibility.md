# Concurrency and visibility

When a change becomes visible to other sessions, how a transaction holds work, and where locks sit. Related: [transactions](../develop/transactions.md), [write path](write-path-staging.md), [ORCHID](orchid-consensus.md).

## Autocommit versus open transaction

| Mode | Behaviour |
|------|-----------|
| **Autocommit** | Each statement is its own unit. On success the change passes admission (ORCHID when durable/replicated) and the journal, then appears in the map for other sessions. |
| **Open TX** (`BEGIN` … `COMMIT` / `ROLLBACK`) | Mutations accumulate in the session dirty buffer (`SqlTxBuffer`). Outsiders do not see them as committed until `COMMIT` succeeds. |

DDL inside an open transaction is **rejected**. Change schema in autocommit.

`ROLLBACK` drops the dirty buffer. Anything already in the OpLog after a successful commit is not undone by a session rollback — time travel needs [PITR](../configure-and-operate/operations/pitr.md).

## Commit order under durability / replication

A successful `COMMIT` on a durable node (and with peers when replication is on) means, in order:

1. Record locks acquired for the keys touched.
2. ORCHID admission: phase sync and digest quorum for the topology.
3. OpLog persistence (`fsync` on production profiles).
4. Apply into the in-memory map and index queues.
5. Only then are the rows visible to concurrent readers as committed.

If any step fails, the client gets a reject. Grid does not expose a half-applied commit — see [architecture](architecture-overview.md).

## Record locks

SQL transactions take fair locks on `(table, key)` via `SqlRecordLockManager`.

- Blocking wait runs on the logic virtual-thread pool, **not** on the Netty event loop.
- Contended keys serialize; Chaos/Stress mixes show higher p95 — expected ([capacity](../performance/capacity-slo.md)).
- Locks are held for the TX and released on `COMMIT` / `ROLLBACK`.

## Connection and TxContext

| Concept | Role |
|---------|------|
| `Connection` / TCP | Transport. One socket carries many logical sessions. |
| `TxContext` | Independent transaction plus PREPARE handle. |
| `maxTxContexts` | Soft cap on concurrent logical sessions on that TCP. |

Parallel application work = several `begin()` / `TxContext`s on one connection, not N sockets per fan-out. One `ConnectionFactory` (or DataSource) per process; `dispose()` on shutdown.

`.block()` only on the application edge (`main`, CLI, tests), never inside library reactive pipelines.

## PREPARE

`PREPARE` stores an executable handle on the session. The body comes from the ANTLR char-stream interval (spaces preserved). Executing a prepared statement follows the same visibility rules as ad-hoc SQL inside a transaction.

## Replica reads

A replica may serve reads but does **not** guarantee read-your-writes relative to a just-completed writer commit. If `applyLagStale`, catch up first — do not “fix” it in the client ([replica reads](../configure-and-operate/operations/replica-reads.md)).

## What Grid does not claim

- No classic MVCC snapshot model in SQL: visibility is commit order plus a dirty buffer.
- No dual writers: one phase-ranked proposer; role change is explicit ([promote](../configure-and-operate/operations/ha-promote.md)).
- No XA / two-phase commit across foreign systems.

## Related

- [Transactions API](../develop/transactions.md)
- [SQL support matrix](../sql/support-matrix.md)
- [Production checklist](../getting-started/production-checklist.md)