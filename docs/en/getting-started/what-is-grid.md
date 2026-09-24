# Introduction

Grid is a distributed SQL database. Schema and data are described in SQL, hot rows are served from memory, and — when durability is on — every change is written to the OpLog journal and to sealed map files (`.gmap` / `.sbpt`) before readers can see it. Across nodes, writes are admitted by ORCHID: a row becomes visible only after the journal write and digest agreement.

The shortest path to a working node is the `capacity` profile: build the starter, start one durable node, run DDL and DML over `grid://`, and add a `primary` / `replica` pair when you need high availability. Steps: [quick start](quick-start.md).

## What the product includes

| Layer | Role |
|-------|------|
| SQL + catalog | DDL/DML, Simplified SQL (ANTLR), tables without a required Java domain type |
| Memory | Working set (`GridScalableMap` + indexes) — an accelerator, not the sole source of truth when durable |
| Disk | OpLog journal + sealed GMAP; recovery after restart |
| Cluster | ORCHID, Netty replication, writer hand-off via `ServerMeta` / `PROMOTE_NOTIFY` |
| Client | `grid://` (reactive) or `jdbc:grid://` (JDBC) in `grid-sql-client` |

Durability (`grid.durability.enabled`) and replication (`grid.replication.enabled`) are **separate** switches. A single durable node with no peers is a normal, supported configuration.

Complete dialect and cluster inventory: [features](features.md). The reasoning behind the design: [why Grid](positioning.md).

## Typical scenarios

| Goal | Start here | Then |
|------|------------|------|
| Try SQL locally | [quick start](quick-start.md) | [connect clients](connect-clients.md) |
| One durable node for keeps | [durability](../configure-and-operate/configuration/durability.md) | [backup / restore](../configure-and-operate/operations/backup-restore.md) |
| Primary + replica | [start a cluster](start-cluster.md) | [promote](../configure-and-operate/operations/ha-promote.md) |
| Go-live | [production checklist](production-checklist.md) | [monitoring](../configure-and-operate/monitoring.md) |
| Two sites | [multi-site](../configure-and-operate/operations/multi-dc.md) | [failures](../configure-and-operate/operations/failures.md) |
| Visibility and TX | [concurrency and visibility](../understand/concurrency-and-visibility.md) | [transactions](../develop/transactions.md) |
| Dialect limits | [SQL support matrix](../sql/support-matrix.md) | [DDL](../sql/ddl.md) / [DML](../sql/dml.md) |
| Incident | [failures](../configure-and-operate/operations/failures.md) | [PITR](../configure-and-operate/operations/pitr.md) |
| Schema in an IDE | [JDBC client](../develop/jdbc-tooling.md) | [security](../configure-and-operate/operations/security.md) |

## How a write works (short)

1. The client sends SQL over TCP (demo port **15432**).
2. With durability / replication: ORCHID admission → OpLog append (`fsync` on production profiles) → in-memory map and index queues.
3. Only then is the row visible to other sessions as committed.
4. Replicas catch up the journal; do not force reads from a lagging replica.

Details: [write path](../understand/write-path-staging.md), [architecture overview](../understand/architecture-overview.md).

## Client

Applications depend on `grid-sql-client` and connect over `grid://` (reactive) or `jdbc:grid://` (JDBC) — one protocol, two APIs. A single TCP connection carries many logical transactions, bounded by `maxTxContexts`. Details: [connect clients](connect-clients.md), [JDBC client](../develop/jdbc-tooling.md).

## Boundaries

- Services speak only `grid://` or `jdbc:grid://` (Grid frames).
- One `dataDir` per node on local disk — not NFS/SAN for the whole cluster.
- One writer at a time; role change via `PROMOTE_NOTIFY` / `rediscoverWriter()`, not by rotating URL hosts.
- Memory holds the working set; with durability on, sealed files and the OpLog are authoritative.
- No TLS on the SQL port — terminate TLS in front of the node ([security](../configure-and-operate/operations/security.md)).

## Capacity orientation

Planning figures for write, read, and mixed load on the lab host (two nodes, `fsync: true`), together with the measurement conditions, live in [capacity](../performance/capacity-slo.md). Demo ports: SQL **15432** / **15433**, replication **5615** / **5616**.

**Related:** [architecture overview](../understand/architecture-overview.md), [durability](../configure-and-operate/configuration/durability.md), [replication](../configure-and-operate/configuration/replication.md), [failures](../configure-and-operate/operations/failures.md).