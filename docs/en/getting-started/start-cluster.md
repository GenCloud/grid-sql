# Start a cluster

The smallest HA setup is two nodes, `primary` and `replica`. Both write to disk, both list each other as peers, and each keeps its own data directory.

## Local 1+1 pair

```mermaid
flowchart LR
  subgraph clients [Clients]
    App[App_grid_url]
  end
  subgraph pair [One_DC_1plus1]
    P["primary SQL :15432 repl :5615"]
    R["replica SQL :15433 repl :5616"]
  end
  App -->|writes_grid://| P
  App -.->|optional_reads| R
  P <-->|Netty_replication| R
```

Build the application jar once:

```powershell
mvn -o -pl grid-sql-server-starter -am package -DskipTests
```

Start two processes with the ready-made profiles:

```powershell
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=primary
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=replica
```

| Process | Profile | SQL | Replication | Actuator | `dataDir` |
|---------|---------|----:|------------:|---------:|-----------|
| primary | `primary` | **15432** | **5615** | **7777** | `./data-primary/…` |
| replica | `replica` | **15433** | **5616** | **7778** | `./data-replica/…` |

Both profiles ship with `fsync: true`, distinct Actuator ports, and peers already pointed at each other, so a local stand needs no hand-edited configuration.

Three things trip people up most often:

- **Each node needs its own `dataDir`.** A shared directory or a cluster-wide network share is not supported.
- **Do not mix the ports.** 15432 and 15433 are SQL for clients; 5615 and 5616 are the replication transport. A client on a replication port gets `bad frameLen …`.
- **The Jepsen image is not a stand.** Build the cluster from `grid-sql-server-starter`; `grid-sql-jepsen-starter` is only the consistency harness.

## Check the pair

```
grid://app:secret@127.0.0.1:15432,127.0.0.1:15433/public
```

Create a table and write a row on the primary, then read it back from the replica (`readEndpoints=127.0.0.1:15433`). Node health is visible through Actuator: `gridReadiness` covers both engine and replication readiness, so it is safe to use as a readiness probe.

```powershell
curl http://127.0.0.1:7777/health/readiness
curl http://127.0.0.1:7778/health/readiness
```

### Verify and common failures

| Check | Expect |
|-------|--------|
| Both readiness probes UP | SQL listening; with replication — ORCHID synced |
| One `writerEligible: true` | Usually the primary; client meta matches |
| Smoke write on **15432**, read via `readEndpoints=…:15433` | Row visible after catch-up (`applyLagStale` false) |

| Symptom | Likely cause |
|---------|--------------|
| `bad frameLen …` on the client | Client pointed at a **replication** port (5615/5616), not SQL |
| Second process on the same `dataDir` | Dual writer / torn files — stop the extra process |
| Readiness DOWN forever | Peers unreachable or ORCHID not synced — check `peers` and Actuator details |
| AUTH fail on replica after `CREATE USER` on primary only | `privileges.meta` is per-node — [security](../configure-and-operate/operations/security.md) |

## Single node without replicas

```mermaid
flowchart LR
  App[Application] -->|grid://_SQL| Solo["capacity node\ndurability on\nreplication off"]
  Solo --> Disk[dataDir_OpLog_sealed]
```

Durability and replication are independent switches. The `capacity` profile starts a solo durable node with `grid.durability.enabled: true`, `grid.replication.enabled: false`, and `fsync: true` — an honest single-machine ceiling that does not wait for peers, and not a lab mode with fsync disabled.

```powershell
java -jar … --spring.profiles.active=capacity
```

A three-node ring in one DC and its failover behaviour: [HA under load](../configure-and-operate/operations/cluster-ha-highload.md).

## Your own configuration

The starter profiles are demos. For a real stand change at least `node-id`, `cluster-id`, the `transport` addresses, the `peers` list, and the data directories. Key-by-key walkthroughs: [replication](../configure-and-operate/configuration/replication.md), [durability](../configure-and-operate/configuration/durability.md), [SQL server](../configure-and-operate/configuration/sql-server.md), [Spring Boot](../develop/spring-boot.md).

Before the stand takes production traffic, walk the [production checklist](production-checklist.md).

## Compose and multiple sites

Ready topologies live under `examples/compose/`: [Compose deploy](../configure-and-operate/operations/deploy-compose.md). The image is built from `grid-sql-server-starter`.

For two sites, enable `grid.replication.cross-dc` with `ASYNC_SHIP` or `SYNC_VOTERS_ACROSS_DC`: [multi-site](../configure-and-operate/operations/multi-dc.md).

## Next

- Failover and writer hand-off: [promote a node](../configure-and-operate/operations/ha-promote.md)
- Offloading reads to replicas: [replica reads](../configure-and-operate/operations/replica-reads.md)
- Metrics and probes: [monitoring](../configure-and-operate/monitoring.md)
- Backups and point-in-time recovery: [PITR](../configure-and-operate/operations/pitr.md)

**Related:** [quick start](quick-start.md), [connect clients](connect-clients.md), [production checklist](production-checklist.md).
