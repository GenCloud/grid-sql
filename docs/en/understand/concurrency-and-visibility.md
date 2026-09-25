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
- Wait ceiling: `grid.sql.lock-wait-timeout-ms` (default `8000`) — beyond that the client sees `lock wait timeout` ([SQL server](../configure-and-operate/configuration/sql-server.md)).

### `FOR UPDATE` and peer locks

`SELECT … FOR UPDATE` takes the same record locks as a write path so a concurrent TX cannot change those keys until this TX ends. With replication on, peer lock agents are wired from **replication `peers`** (not a separate SQL peer list) — local-only when replication is off or the peer list is empty. A Netty error on a peer lock is a **reject** (fail-closed), not a silent local-only commit. Details: [replica reads](../configure-and-operate/operations/replica-reads.md).

`FOR UPDATE SKIP LOCKED` skips keys already locked by another TX instead of waiting (until `lock-wait-timeout-ms`). Use it for competing workers that can process another row; do not use it when every selected key must be held.

## Savepoints

Inside an open transaction, `savepoint` / `rollbackTo` / `release` affect only the in-memory dirty buffer. They never rewrite the OpLog or call ORCHID. Sequences are not rewound. API examples: [transactions](../develop/transactions.md).

## Connection and TxContext

| Concept | Role |
|---------|------|
| `Connection` / TCP | Transport. One socket carries many logical sessions. |
| `TxContext` | Independent transaction plus PREPARE handle. |
| `maxTxContexts` | Client soft cap on concurrent logical sessions on that TCP (default **256**); server hard-cap **8** — open another `Connection` when exhausted. |

Parallel application work = several `begin()` / `TxContext`s on one connection, not a new socket per parallel worker. One `ConnectionFactory` (or DataSource) per process; `dispose()` on shutdown. The **server** channel default is **8** and Boot does not raise it from YAML — beyond that open another `Connection` ([SQL server](../configure-and-operate/configuration/sql-server.md)).

`.block()` only on the application edge (`main`, CLI, tests), never inside library reactive pipelines.

## PREPARE

`PREPARE` stores an executable handle on the session. The body comes from the ANTLR char-stream interval (spaces preserved). Executing a prepared statement follows the same visibility rules as ad-hoc SQL inside a transaction.

## Replica reads

A replica may serve reads but does **not** guarantee read-your-writes relative to a just-completed writer commit. After `COMMIT` succeeds on the writer, a replica may still return the previous row until it applies that OpLog unit — that gap is apply lag, not a client bug.

| Need | Where to read |
|------|----------------|
| See your own write immediately | Writer pin (default for open TX / DML; or `PRIMARY` preference) |
| Scale read-only SELECT/EXPLAIN | Replica endpoints when `applyLagStale` is false |
| Replica reports `applyLagStale` | Catch up first — do not “fix” lag in the client |

Details and routing: [replica reads](../configure-and-operate/operations/replica-reads.md).

## Model boundaries

Visibility is commit order plus a dirty buffer — not classic MVCC snapshots. One phase-ranked proposer writes; role change is explicit ([promote](../configure-and-operate/operations/ha-promote.md)). XA / two-phase commit across foreign systems is not supported.

## Related

- [Transactions API](../develop/transactions.md)
- [SQL support matrix](../sql/support-matrix.md)
- [Production checklist](../getting-started/production-checklist.md)