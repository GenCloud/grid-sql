# Grid documentation

Grid is a distributed SQL database: in-memory speed, durable on-disk storage, and ORCHID replication across nodes. Applications connect with `grid-sql-client` over `grid://` (reactive) or `jdbc:grid://` (JDBC) — one protocol, two stable APIs.

Russian mirror: [`docs/ru/`](ru/README-ru.md). Same page layout in both languages.

## Where to start

### New to Grid

- [Introduction](en/getting-started/what-is-grid.md)
- [Quick start](en/getting-started/quick-start.md)
- [Start a cluster](en/getting-started/start-cluster.md)
- [Production checklist](en/getting-started/production-checklist.md)

### Developers

- [Java client](en/develop/java-client.md)
- [Runnable examples](../examples/README.md)
- [SQL](en/sql/fundamentals.md)
- [Transactions](en/develop/transactions.md)
- [jOOQ DSL](en/develop/jooq.md)
- [Features](en/getting-started/features.md)

### Operations

- [Durability](en/configure-and-operate/configuration/durability.md)
- [Replication](en/configure-and-operate/configuration/replication.md)
- [Monitoring](en/configure-and-operate/monitoring.md)
- [Failures](en/configure-and-operate/operations/failures.md)
- [Security](en/configure-and-operate/operations/security.md)
- [Backup and restore](en/configure-and-operate/operations/backup-restore.md)
- [Upgrade a node](en/configure-and-operate/operations/upgrade.md)
- [Promote a node](en/configure-and-operate/operations/ha-promote.md)
- [Replica reads](en/configure-and-operate/operations/replica-reads.md)
- [Overlay PIN](en/configure-and-operate/configuration/overlay-pin.md)
- [Point-in-time recovery (PITR)](en/configure-and-operate/operations/pitr.md)

### API

- [API reference](en/api-reference/README.md)
- [JDBC client](en/develop/jdbc-tooling.md)

## Documentation sections

| Section | |
|---------|--|
| [Getting started](en/getting-started/what-is-grid.md) | Introduction, install, first query |
| [Develop](en/develop/java-client.md) | Clients, Spring Boot, transactions |
| [SQL](en/sql/fundamentals.md) | Dialect, DDL/DML, indexes, plans |
| [Configure and operate](en/configure-and-operate/configuration/sql-server.md) | Node config, HA, multi-site |
| [Understand](en/understand/architecture-overview.md) | Architecture, storage, ORCHID |
| [API reference](en/api-reference/README.md) | Java SPI |
| [Tools](en/tools/sql-cli.md) | CLI, load harness, glossary |
| [Performance](en/performance/capacity-slo.md) | Lab measurements and thresholds |

### Getting started

| Page | |
|------|--|
| [Introduction](en/getting-started/what-is-grid.md) | |
| [Features](en/getting-started/features.md) | |
| [Why Grid](en/getting-started/positioning.md) | |
| [Quick start](en/getting-started/quick-start.md) | |
| [Cluster](en/getting-started/start-cluster.md) | |
| [Connect](en/getting-started/connect-clients.md) | |
| [Production checklist](en/getting-started/production-checklist.md) | |
| [Best practices](en/getting-started/best-practices.md) | |

### Develop

| Page | |
|------|--|
| [Java client](en/develop/java-client.md) | |
| [JDBC client](en/develop/jdbc-tooling.md) | |
| [jOOQ DSL](en/develop/jooq.md) | |
| [Spring Boot](en/develop/spring-boot.md) | |
| [Transactions](en/develop/transactions.md) | |
| [Result streaming](en/develop/wire-streaming.md) | |

### SQL

| Page | |
|------|--|
| [Fundamentals](en/sql/fundamentals.md) | |
| [DDL](en/sql/ddl.md) · [DML](en/sql/dml.md) · [Types](en/sql/types.md) · [Indexes](en/sql/indexes.md) | |
| [EXPLAIN and AQE](en/sql/explain-and-aqe.md) | |
| [Support matrix](en/sql/support-matrix.md) | |

### Configure and operate

| Page | |
|------|--|
| [Durability](en/configure-and-operate/configuration/durability.md) | |
| [Replication](en/configure-and-operate/configuration/replication.md) | |
| [SQL server](en/configure-and-operate/configuration/sql-server.md) | |
| [Key pin (PIN)](en/configure-and-operate/configuration/overlay-pin.md) | |
| [Monitoring](en/configure-and-operate/monitoring.md) | |
| [Failures](en/configure-and-operate/operations/failures.md) | |
| [Security](en/configure-and-operate/operations/security.md) | |
| [Backup and restore](en/configure-and-operate/operations/backup-restore.md) | |
| [Upgrade a node](en/configure-and-operate/operations/upgrade.md) | |
| [Promote a node](en/configure-and-operate/operations/ha-promote.md) | |
| [Replica reads](en/configure-and-operate/operations/replica-reads.md) | |
| [Multi-site](en/configure-and-operate/operations/multi-dc.md) | |
| [PITR](en/configure-and-operate/operations/pitr.md) | |
| [HA under load](en/configure-and-operate/operations/cluster-ha-highload.md) | |
| [Multi-site under load](en/configure-and-operate/operations/cluster-multidc-highload.md) | |
| [Compose deploy](en/configure-and-operate/operations/deploy-compose.md) | |

### Understand

| Page | |
|------|--|
| [Architecture](en/understand/architecture-overview.md) | |
| [Storage (GMAP)](en/understand/storage-sealed-gmap.md) | |
| [ORCHID](en/understand/orchid-consensus.md) | |
| [Concurrency and visibility](en/understand/concurrency-and-visibility.md) | |
| [Replication network](en/understand/replication-network.md) | |
| [Write path](en/understand/write-path-staging.md) | |
| [Overlay and placement](en/understand/overlay-and-swarm.md) | |
| [Replication state](en/understand/replication-state.md) | |
| [Bio metaphors and claim bounds](en/understand/bio-inspired.md) | |

### Performance

Write, read, and mixed planning figures for the lab host (two nodes, `fsync: true`) live in [capacity](en/performance/capacity-slo.md). Default ports: SQL **15432** / **15433**, replication **5615** / **5616**.

| Page | |
|------|--|
| [Capacity](en/performance/capacity-slo.md) | |
| [Results](en/performance/results.md) | |
| [Methodology](en/performance/methodology.md) | |
| [Duplex encoding](en/performance/perf-duplex.md) | |
| [ORCHID write path](en/performance/perf-bio-consensus.md) | |

### Tools

| Page | |
|------|--|
| [SQL CLI](en/tools/sql-cli.md) | |
| [JMeter load](en/tools/jmeter-load-slo.md) | |
| [Glossary](en/tools/glossary.md) | |

### Internal

Engineering notes for contributors:

- [Development](en/internal/development.md)
- [ORCHID TLA](en/internal/orchid-tla.md)
- [Bug journal](en/internal/bug-journal.md)
- [EncodeBuffers](en/internal/encode-buffers.md)
- [GridFs](en/internal/grid-fs.md)
