# Features

What Grid supports today: SQL, storage, transactions, cluster behaviour, and clients.

## Scope at a glance

| Area | In product |
|------|------------|
| SQL + TX + hot RAM + disk | Yes — one stack |
| Clients | `grid://` (reactive) and `jdbc:grid://` (JDBC) |
| Cluster | ORCHID, peer OpLog ship, HA hand-off, multi-site modes |
| Foreign SQL wire / shared NFS `dataDir` / dual writers | No |

## SQL

- Queries are parsed with an ANTLR grammar: Simplified SQL, a bounded dialect — not a full SQL clone.
- DDL for tables, indexes, and constraints.
- DML: `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE TABLE`, `UPSERT`, `INSERT … ON CONFLICT`, and a subset of `MERGE`.
- SELECT: filters, INNER / LEFT / RIGHT / FULL JOIN, multi-column `GROUP BY` and `PARTITION BY`, `ORDER BY`, `LIMIT`, `OFFSET`.
- `EXISTS` / `NOT EXISTS`, CTEs (`WITH`), views, window functions, scalar and table user functions — within the dialect.
- `PREPARE` and bound parameters.
- `EXPLAIN`, and adaptive execution (AQE) for heavy plans.
- Rows are binary records with per-field access; a Java entity class is optional.

See [SQL fundamentals](../sql/fundamentals.md), [DDL](../sql/ddl.md), [DML](../sql/dml.md), [support matrix](../sql/support-matrix.md).

## Storage

- Data is sharded in memory; indexes are updated through a queue.
- On disk: sealed map files (GMAP) and the mutation journal (OpLog).
- Startup either preloads everything (`FULL`) or maps files and loads on demand (`LAZY`).
- A working-set ceiling evicts cold keys and reloads them from disk on miss.
- B+ tree indexes over one or several columns; bitmap indexes only via an explicit `CREATE BITMAP INDEX`.
- Secondary indexes can be sealed as well, so an index miss does not fall back to a partition scan.
- Local durability and peer replication are separate switches: one node can persist without replicas.

See [storage](../understand/storage-sealed-gmap.md), [durability](../configure-and-operate/configuration/durability.md).

## Transactions

- One TCP connection carries several independent transactions.
- Changes stay private to their transaction until COMMIT.
- Per-key record locks; `FOR UPDATE` / `SKIP LOCKED` on the writer (and peers when Dist FOR UPDATE is on — from replication `peers`, not `distributed-peers`). Details: [replica reads](../configure-and-operate/operations/replica-reads.md).
- DDL inside an open transaction is rejected.

## Privileges (RBAC)

- `CREATE USER` / `DROP USER` / `ALTER USER … PASSWORD`, `CREATE ROLE` / `DROP ROLE`, `GRANT` / `REVOKE`, and `GRANT ROLE … TO user`, stored in `privileges.meta` (per-node local file; replication does not ship it — apply on every SQL node apps may hit). See [security](../configure-and-operate/operations/security.md).
- An empty catalog means open access, and the first user created becomes administrator.
- Once users exist, AUTH on the wire is mandatory and table privileges are enforced, including both sides of a JOIN.
- There is no separate `REVOKE ROLE`; membership is removed with `DROP ROLE`.

See [security](../configure-and-operate/operations/security.md), [DDL](../sql/ddl.md).

## Cluster

- Writers synchronise on ORCHID phase and a digest quorum before a commit lands.
- The journal ships to peers over TCP; replicas apply it and repair divergence.
- Replica placement, shard moves, and async or sync shipping between sites are available.
- Replica reads follow an explicit policy.
- If a channel is lost, the apply role can move to another node.
- Regions are pinned to nodes through `REGION_CLAIM`.

See [ORCHID](../understand/orchid-consensus.md), [replication](../configure-and-operate/configuration/replication.md).

## Clients and tools

- Reactive: `grid-sql-client`, URL `grid://`, `ConnectionFactory` / `TxContext`.
- JDBC: the same artifact, URL `jdbc:grid://`, driver `org.genfork.grid.jdbc.GridDriver` — the stable synchronous peer of the reactive API, for DataSource/DAO stacks and IDEs.
- SQL CLI, Spring Boot auto-configuration, and a standalone SQL server jar.
- Apache JMeter suites over `grid://` — not over JDBC, where synchronous calls distort p95.
- Jepsen checks consistency on a single site and across sites; capacity numbers come from the load harness.

See [connect clients](connect-clients.md), [JDBC client](../develop/jdbc-tooling.md).

## Lab throughput

Planning numbers and regression floors live in [capacity](../performance/capacity-slo.md); full run tables in [results](../performance/results.md). Re-measure on a calm host before capacity claims — this page is not a live scoreboard.

**Related:** [introduction](what-is-grid.md), [why Grid](positioning.md), [connect clients](connect-clients.md).
