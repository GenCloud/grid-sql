# Why Grid

Grid keeps hot data in memory, writes durably through a disk journal first, agrees between nodes with ORCHID, and exposes one application protocol — `grid://`. Teams often start from a disk database plus a separate in-memory cache; this page explains what the combined product buys instead.

## One system instead of a stack

A disk SQL database for durability plus a cache for hot reads means two clients, two failure domains, cache/database drift, and separate operations for each layer.

In Grid, schema, transactions, memory, and disk are one product:

- SQL DDL/DML and a table catalog;
- a RAM working set backed by sealed files and the OpLog on disk;
- clustered writes admitted by ORCHID before a row becomes visible;
- one application protocol: `grid://` (reactive) or `jdbc:grid://` (JDBC).

Fewer moving parts means fewer places where the cache is already updated and the journal is not.

## Durability and replication are separate switches

| Switch | What it gives |
|--------|----------------|
| `grid.durability.enabled` | Local ORCHID + OpLog + sealed; a solo node with no peers is supported |
| `grid.replication.enabled` | Ship the journal to peers, quorum, catch-up, repair |

Turn them on independently. Details: [durability](../configure-and-operate/configuration/durability.md), [replication](../configure-and-operate/configuration/replication.md).

## Writes you can trust

With durability on, a row becomes visible **after** the journal write and the digest quorum, not "memory first, disk later". A failure at any step is rejected to the client rather than left half applied.

That gives you:

- `fsync` and grouped OpLog flush on the real `primary` / `replica` profiles;
- one writer at a time (phase-ranked proposer), with no dual-master merge;
- writer hand-off carried by the protocol (`ServerMeta` / `PROMOTE_NOTIFY`), without rotating hosts in the URL by hand.

More: [architecture overview](../understand/architecture-overview.md), [ORCHID](../understand/orchid-consensus.md), [promote a node](../configure-and-operate/operations/ha-promote.md).

## Speed where the hot set lives

Memory accelerates the working set; the dataset does not have to fit in RAM. Cold keys live in sealed storage and load back on miss. Queries stay SQL, served by B+ tree and bitmap indexes, and heavy SELECT plans run through adaptive execution (AQE).

Throughput figures for the lab host (two nodes, `fsync: true`) and the conditions they were measured under are in [capacity](../performance/capacity-slo.md). They come from the same stack applications use: `grid://` with durability enabled.

## From one node to multiple sites

A solo durable node (`capacity`), a 1+1 pair, an N=3 ring, Active/Hold across sites, `ASYNC_SHIP` and `SYNC_VOTERS` — the same product and the same data model throughout.

- [start a cluster](start-cluster.md)
- [HA in one DC](../configure-and-operate/operations/cluster-ha-highload.md)
- [multi-site](../configure-and-operate/operations/multi-dc.md)

Shard placement and PIN are levers on top of that same replication path: [overlay and placement](../understand/overlay-and-swarm.md).

## A client without protocol surprises

Applications use `grid://` with `ConnectionFactory`, or JDBC with `jdbc:grid://`: one product protocol, many logical sessions on one socket (`maxTxContexts`), result streaming, and PREPARE. One driver stack for apps — not a second protocol beside Grid.

See [Java client](../develop/java-client.md), [connect clients](connect-clients.md), [features](features.md).

## What Grid leaves outside the product

- Two writers at once and merging their journals.
- A shared `dataDir` on NFS/SAN for the whole cluster — the directory belongs to one node.
- TLS on the SQL port out of the box — terminate outside (proxy / load balancer); see [security](../configure-and-operate/operations/security.md).
- Read-your-writes through a lagging replica without going via the writer.

## When Grid is the right choice

| Need in one product | Grid covers |
|---------------------|-------------|
| SQL + transactions + hot memory + disk | Yes |
| Clustered write journalled before visibility | ORCHID + OpLog + sealed files |
| One application protocol for all services | `grid://` |
| HA and multi-site without a data model change | 1-DC / Active–Hold / `ASYNC_SHIP` · `SYNC_VOTERS` |
| Measurements taken on the protocol applications use | [capacity](../performance/capacity-slo.md) |

Before production traffic, walk the [production checklist](production-checklist.md). Load figures live in [capacity](../performance/capacity-slo.md); run summaries in [results](../performance/results.md).

**Related:** [introduction](what-is-grid.md), [features](features.md), [quick start](quick-start.md), [architecture overview](../understand/architecture-overview.md).