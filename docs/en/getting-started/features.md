# Features

What Grid supports today: SQL, storage, transactions, cluster behaviour, and tooling.

## Scope at a glance

| Need | Grid |
|------|------|
| SQL, transactions, hot memory, and disk in one product | Yes |
| Foreign SQL wire for services | No — only `grid://` / `jdbc:grid://` (Grid frames) |
| Shared NFS `dataDir` for the cluster | No — one directory per node |
| Two writers at once | No — one writer, hand-off via `PROMOTE_NOTIFY` |

## SQL

- Queries are parsed with an ANTLR grammar: Simplified SQL, a bounded dialect — not a full SQL clone.
- DDL for tables, indexes, and constraints.
- DML: `INSERT`, `UPDATE`, `DELETE`, `UPSERT`, `INSERT … ON CONFLICT`, and a subset of `MERGE`.
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
- Per-key record locks; `FOR UPDATE` and `SKIP LOCKED` on the writer node, and on peers when Dist FOR UPDATE agents are configured.
- DDL inside an open transaction is rejected.

## Privileges (RBAC)

- `CREATE USER` / `DROP USER` / `ALTER USER … PASSWORD`, `CREATE ROLE` / `DROP ROLE`, `GRANT` / `REVOKE`, and `GRANT ROLE … TO user`, stored in `privileges.meta`.
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

## Measured throughput

Lab host, two nodes, `fsync: true`. The lower bound is the regression floor at roughly 95% of the planning figure.

| Scenario | ops/s | Lower bound (~95%) |
|----------|------:|-------------------:|
| Write only | **4921.975** | ≈ 4676 |
| Read only | **52261…59430** | ≈ 52261 |
| Mixed capacity | **8772…11352** | ≈ 8333 |
| Long mixed run (128 threads, 120 s) | **9013.292** | — |
| Single node: write / read / mixed | **7095** / **56564** / **19526** | — |

Point reads and COUNT JOIN average about **0.5 ms** on this host, with read throughput near **55k** ops/s. A sealed key lookup in memory-plus-disk mode over 100k rows takes **0.313 µs** (ceiling ≈ 0.358). Conditions and full tables: [capacity](../performance/capacity-slo.md), [results](../performance/results.md).

**Related:** [introduction](what-is-grid.md), [why Grid](positioning.md), [connect clients](connect-clients.md).
