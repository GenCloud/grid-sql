# Introduction

You need a SQL database where hot rows come from memory, and — with durability on — every change hits the journal before any reader can see it. Across nodes ORCHID admits the write: checksums match on a majority from the configuration → the row is visible; otherwise the client gets a reject. Better a reject than two journals.

The shortest path to a working node is the `capacity` profile: build the starter, start one durable node, run DDL/DML over `grid://`, add a `primary` / `replica` pair when you need HA. Steps: [quick start](quick-start.md).

## What the product includes

| Layer | Role |
|-------|------|
| SQL + catalog | DDL/DML, Simplified SQL (ANTLR), tables without a required Java domain type |
| Memory | Hot set — an accelerator, not the sole source of truth when durable |
| Disk | OpLog journal + sealed files; recovery after restart |
| Cluster | ORCHID, Netty replication, writer hand-off via `ServerMeta` / `PROMOTE_NOTIFY` |
| Client | `grid://` (reactive) or `jdbc:grid://` (JDBC) in `grid-sql-client` |

Durability (`grid.durability.enabled`) and replication (`grid.replication.enabled`) are **separate** switches. A single durable node with no peers is a normal, supported configuration.

Full inventory: [features](features.md). Why it is shaped this way: [why Grid](positioning.md).

## Where to start

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

## How a write works

1. The client sends SQL over TCP (demo port **15432**).
2. With durability / replication: ORCHID admission → OpLog append (`fsync` on working profiles) → in-memory map and indexes.
3. Only then is the row visible to other sessions as committed.
4. Peers catch up the journal; do not force reads from a lagging replica.

Details: [write path](../understand/write-path-staging.md), [architecture overview](../understand/architecture-overview.md).

## Client

Applications depend on `grid-sql-client` and connect over `grid://` or `jdbc:grid://` — one protocol, two APIs. One TCP carries many logical transactions: client URL default `maxTxContexts` **256**, server channel hard-cap **8** (Boot does not raise it from YAML) — open another `Connection` when exhausted. See [connect clients](connect-clients.md), [JDBC client](../develop/jdbc-tooling.md).

## What not to expect

- Services speak only `grid://` / `jdbc:grid://`.
- One `dataDir` per node on local disk — not NFS/SAN for the whole cluster.
- One writer at a time; role change via `PROMOTE_NOTIFY` / `rediscoverWriter()`, not by rotating URL hosts.
- Memory holds the hot set; with durability on, sealed files and the OpLog are authoritative.
- No TLS on the SQL port — terminate outside ([security](../configure-and-operate/operations/security.md)).

## Capacity orientation

Write, read, and mixed figures on the lab host (two nodes, `fsync: true`): [capacity](../performance/capacity-slo.md). Demo ports: SQL **15432** / **15433**, replication **5615** / **5616**.

Next: [architecture overview](../understand/architecture-overview.md), [durability](../configure-and-operate/configuration/durability.md), [replication](../configure-and-operate/configuration/replication.md).
