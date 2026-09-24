# Best practices

Working rules that keep a Grid installation predictable: disk layout, access, recovery, monitoring, schema, and client usage. For a go-live gate in checklist form, see [production checklist](production-checklist.md).

## Operations

- **One `dataDir` per node**, on local disk. A shared directory or an NFS/SAN volume for the cluster is not supported.
- Durability and replication are enabled **separately**: a solo node can write durably with no replicas. Keep `fsync: true` on the OpLog of every durable node.
- **Users and AUTH** before any networked application reaches the SQL port: [security](../configure-and-operate/operations/security.md).
- **Recovery:** enable `oplog-archive` early, take base backups on a schedule, and practise a restore offline: [PITR](../configure-and-operate/operations/pitr.md).
- **Actuator probes** (liveness and readiness) wired up before traffic: [monitoring](../configure-and-operate/monitoring.md).
- **Failure playbooks** for writer loss, ORCHID, multi-site, and stale replicas: [failures](../configure-and-operate/operations/failures.md).
- **Binary upgrades** are a rolling restart against the same `dataDir`: [upgrade](../configure-and-operate/operations/upgrade.md).
- HA clients pin on `writerEligible` and follow `ServerMeta` / `PROMOTE_NOTIFY`: [promote a node](../configure-and-operate/operations/ha-promote.md).
- Reading your own writes through a replica is not guaranteed: [replica reads](../configure-and-operate/operations/replica-reads.md).
- Shard auto-cutover (`apply-auto-cutover`) is on by default; disabling it is a temporary measure: [overlay and placement](../understand/overlay-and-swarm.md).

## Scenario to page

| Scenario | Where |
|----------|-------|
| Solo durable node | [quick start](quick-start.md), [durability](../configure-and-operate/configuration/durability.md) |
| HA on one site | [start a cluster](start-cluster.md), [promote a node](../configure-and-operate/operations/ha-promote.md) |
| Reads offloaded to a replica | [replica reads](../configure-and-operate/operations/replica-reads.md) |
| Restore from the journal | [PITR](../configure-and-operate/operations/pitr.md) |
| Two or more sites | [multi-site](../configure-and-operate/operations/multi-dc.md) |
| Incident or failure | [failures](../configure-and-operate/operations/failures.md) |
| Users and GRANT | [security](../configure-and-operate/operations/security.md) |
| Version change | [upgrade](../configure-and-operate/operations/upgrade.md) |

## Common mistakes

| Mistake | Better |
|---------|--------|
| Shared `dataDir` for two processes | One directory per node, on local disk |
| JDBC used to drive JMeter or capacity runs | JMeter on `grid://`; JDBC for services and IDEs |
| Rotating to the next host in the URL after a promote | `rediscoverWriter()`, or wait for `PROMOTE_NOTIFY` |
| Quoting numbers measured with `fsync: false` | Measure and publish only with `fsync: true` |
| Turning the OpLog archive on "later" | Enable it before the load you may want to roll back |

## Schema and queries

- Declare indexes through SQL DDL. Bitmap indexes only via an explicit `CREATE BITMAP INDEX`.
- A composite index pays off when the filter matches a prefix of its columns.
- Check the plan with `EXPLAIN`: [EXPLAIN and AQE](../sql/explain-and-aqe.md).
- DDL inside an open transaction is rejected — change schema in autocommit.
- Avoid `SELECT *` on hot paths.

## Applications

- Pick one API per stack: reactive (`grid://`) or JDBC (`jdbc:grid://`). Both are stable: [JDBC client](../develop/jdbc-tooling.md).
- One `ConnectionFactory` per process; call `dispose()` on shutdown.
- Parallel transactions are several `TxContext` objects on one TCP connection.
- Use `.block()` only at the application boundary (`main`, CLI, tests).
- Do not hold a transaction open across a call to an external system.
- Fetch large result sets in windows: [result streaming](../develop/wire-streaming.md).

## Measurements

Measure with `fsync: true` only; `fsync: false` isolates a bottleneck in the lab and is not a claimed maximum.

Run one check at a time on an idle host — concurrent checks invalidate the numbers. The internal run order is documented in [methodology](../performance/methodology.md).

Quote the JSON of a specific run: [capacity](../performance/capacity-slo.md), [results](../performance/results.md). Thresholds are a regression floor at roughly 95% of the planning figure, not a target to chase. Drive load with JMeter over `grid://`: [load and SLO](../tools/jmeter-load-slo.md).

## Performance expectations

Writes are bounded by ORCHID admission and the journal, not by spare CPU. Reads scale roughly an order of magnitude better than writes — that is the expected shape. Extra read headroom comes from replicas, not from more write threads.

**Related:** [production checklist](production-checklist.md), [why Grid](positioning.md), [transactions](../develop/transactions.md), [capacity](../performance/capacity-slo.md).
