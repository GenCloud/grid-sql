# Why Grid

Teams often pair a disk SQL database “for keeps” with a separate in-memory cache “for hot reads”. That means two clients, two failure domains, and a permanent question: did the cache update while the journal did not?

Grid puts those layers in one product: hot data in memory, durable writes through a disk journal first, node agreement with ORCHID, and one application protocol — `grid://` (reactive) or `jdbc:grid://` (JDBC).

## One system instead of a stack

In Grid, schema, transactions, memory, and disk are one contour:

- SQL DDL/DML and a table catalog;
- a hot RAM set backed by sealed files and the OpLog;
- clustered writes that ORCHID admits **before** a row becomes visible;
- one protocol for every service.

Fewer moving parts means fewer places where layers drift apart.

## Durability and replication are separate switches

| Switch | What it gives |
|--------|----------------|
| `grid.durability.enabled` | Local ORCHID + OpLog + sealed files; a solo node with no peers is supported |
| `grid.replication.enabled` | Ship the journal to peers, quorum, catch-up, repair |

Turn them on independently. Details: [durability](../configure-and-operate/configuration/durability.md), [replication](../configure-and-operate/configuration/replication.md).

## Writes you can trust

With durability on, a row is visible **after** the journal and checksum agreement — not “memory first, disk later”. A failure at any step returns an error to the client with no half-applied row. Better a reject than two journals.

That means: `fsync` on working profiles; one writer at a time (phase-ranked); role hand-off via `ServerMeta` / `PROMOTE_NOTIFY`, without rotating URL hosts by hand.

More: [architecture overview](../understand/architecture-overview.md), [ORCHID](../understand/orchid-consensus.md), [promote](../configure-and-operate/operations/ha-promote.md).

## Speed where the hot set lives

Memory accelerates hot keys; the whole dataset need not fit in RAM. Cold keys live in sealed files and reload on miss. Queries stay SQL with B+ and bitmap indexes; heavy SELECT uses adaptive execution (AQE).

Lab-host figures (two nodes, `fsync: true`): [capacity](../performance/capacity-slo.md). Measured on the same `grid://` stack applications use.

## From one node to multiple sites

A solo durable node (`capacity`), a 1+1 pair, an N=3 ring, Active/Hold across sites, `ASYNC_SHIP` and `SYNC_VOTERS` — one data model throughout.

- [start a cluster](start-cluster.md)
- [HA in one site](../configure-and-operate/operations/cluster-ha-highload.md)
- [multi-site](../configure-and-operate/operations/multi-dc.md)

Shard placement and PIN sit on the same replication path: [overlay and placement](../understand/overlay-and-swarm.md).

## A client without protocol surprises

One protocol, many logical sessions on one TCP: client `maxTxContexts` default **256**, server channel hard-cap **8** (Boot does not raise it from YAML). Result streaming, PREPARE. No second protocol beside Grid.

See [Java client](../develop/java-client.md), [connect clients](connect-clients.md).

## What Grid leaves outside the product

- Two writers at once and merging their journals.
- A shared NFS/SAN `dataDir` for the whole cluster.
- TLS on the SQL port in the product — terminate outside; [security](../configure-and-operate/operations/security.md).
- Read-your-writes through a lagging replica without going via the writer.

Before production traffic: [production checklist](production-checklist.md). Load figures: [capacity](../performance/capacity-slo.md).

Next: [introduction](what-is-grid.md), [quick start](quick-start.md), [architecture overview](../understand/architecture-overview.md).
