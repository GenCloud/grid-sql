# Spring Boot

Grid embeds into Spring Boot in two different ways, and it matters not to mix them up.

| Process role | What you add | What you get |
|--------------|--------------|--------------|
| **Database node** | `grid-sql-server-starter` (fat jar) or a dependency on `grid-server-core` | Engine, catalog, storage, replication, TCP SQL listener |
| **Client application** | `grid-sql-client` | A `ConnectionFactory` over `grid://`; the engine is not pulled into the process |

A typical application is the second case: the service does not host the database, it connects to it.

Auto-configuration lives in `grid-server-core`: `GridAutoConfiguration` (core and storage), `GridSqlAutoConfiguration` (`SqlServerRuntime` + TCP listener), `GridReplicationAutoConfiguration` (ORCHID, OpLog, peers). All of them key off the `grid.*` prefix.

## Starting a node

```powershell
mvn -o -pl grid-sql-server-starter -am package -DskipTests
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=primary
```

The entry point is `org.genfork.grid.sql.server.GridSqlServerStarter`. If you do not need Spring at all (running from an IDE, a minimal bench), there is `org.genfork.grid.sql.SqlServerMain` without a Boot context.

### Starter profiles

| Profile | SQL | Replication | HTTP | Purpose |
|---------|----:|------------:|-----:|---------|
| `primary` | **15432** | **5615** | 7777 | Writer-eligible node of the demo pair |
| `replica` | **15433** | **5616** | 7778 | Peer of the same pair |
| `capacity` | **15432** | — | 7778 | Single durable node (`replication.enabled: false`, `fsync: true`) |

`capacity` is the honest single-node ceiling: local disk writes are on, and there is no waiting for peers. It is not a "lab mode with fsync disabled".

## Node configuration

Auto-configuration reads the `grid.*` keys below. The YAML is a **`primary` starter profile sample**, not the bare library defaults in `GridConfigurationProperties`.

| Setting | Library default (no profile) | Typical `primary` / `capacity` |
|---------|------------------------------|--------------------------------|
| `durability.hydrate-mode` | `FULL` | `LAZY` |
| `durability.working-set-max-entries` | `0` (unbounded) | e.g. `262144` |
| `replication.op-log.segment-size` | `1024` (MiB) | e.g. `64` |
| `replication.ha.replica-reads-enabled` | `false` | `true` on demo `primary`/`replica` |

Full default tables: [durability](../configure-and-operate/configuration/durability.md), [replication](../configure-and-operate/configuration/replication.md), [SQL server](../configure-and-operate/configuration/sql-server.md). For PITR archive keys (`oplog-archive.*`) and multi-site `region.*`, see those pages — they are off by default.

```yaml
grid:
  sql:
    default-shards: 8
    data-dir: ./data-primary/catalog
  sql-server:
    enabled: true
    host: 0.0.0.0
    port: 15432
  codec:
    duplex:
      enabled: true
      schema-epoch: 1
  overlay:
    enabled: false
    durable: false
  durability:
    enabled: true
    hydrate-mode: LAZY            # profile; library default is FULL
    working-set-max-entries: 262144
    adaptive-disk-first: true
    # oplog-archive.enabled: false   # enable before load you may need to roll back — see PITR
  replication:
    enabled: true
    node-id: primary-1
    cluster-id: example-grid
    ha:
      max-stale-lag: 10000
      replica-reads-enabled: true
    orchid:
      coupling: 15.0
      natural-freq-hz: 1.0
      order-threshold: 0.85
      tick-ms: 10
      digest-quorum: MAJORITY
      max-propose-in-flight: 64
    transport:
      bind-host: 127.0.0.1
      bind-port: 5615
      connect-timeout-ms: 5000
      peers:
        - { id: replica-1, host: 127.0.0.1, port: 5616, dc: dc-a }
    op-log:
      data-dir: ./data-primary/replication
      segment-size: 64
      fsync: true
    repair:
      homologous-enabled: true
      reconcile-interval-ms: 5000
    swarm:
      enabled: true
      score-window-ms: 5000
      migrate-threshold: 0.3
    cross-dc:
      enabled: true
      local-dc: dc-a
      mode: ASYNC_SHIP            # ASYNC_SHIP | SYNC_VOTERS_ACROSS_DC
      batch-max-ops: 256
      batch-max-wait-ms: 20
    flow:
      max-inflight-ops: 10000
```

What is worth understanding about these groups:

| Group | Meaning |
|-------|---------|
| `grid.sql` | Catalog and sharding: `default-shards` is the default shard count for new tables, `data-dir` is the catalog and sealed-file tree |
| `grid.sql-server` | The TCP listener. Enable it **only** on server nodes; a client application does not need it |
| `grid.durability` | Local disk writes: OpLog plus sealed GMAP. Works without replicas (solo) |
| `grid.replication` | Peer exchange: ORCHID, journal shipping, repair, placement. A separate switch from `durability` |
| `grid.replication.op-log.fsync` | `true` for any numbers you intend to quote. `false` is lab only |
| `grid.replication.ha` | Allowed replica lag and permission to read from it |
| `grid.replication.cross-dc` | Delivery between sites |

Two independent switches are the most common source of confusion:

- `grid.durability.enabled: true` + `grid.replication.enabled: false` — one node, data survives restart, no replicas.
- `grid.durability.enabled: true` + `grid.replication.enabled: true` — plus journal shipping to peers, digest quorum, repair.

`dataDir` is **per node**. One directory for two processes, or shared NFS/SAN across a cluster, is not supported.

Key-by-key details: [durability](../configure-and-operate/configuration/durability.md), [replication](../configure-and-operate/configuration/replication.md), [SQL server](../configure-and-operate/configuration/sql-server.md).

## Observability

The starter profiles expose Actuator on a separate HTTP port:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
      base-path: /
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,gridReadiness
```

`gridReadiness` and `gridLiveness` are node state indicators: readiness accounts for engine and replication readiness, so in Kubernetes you can wire it to a readinessProbe. Metrics — [monitoring](../configure-and-operate/monitoring.md).

## The application as a client

In a service application you declare a plain factory bean and reuse it for the whole process:

```java
@Configuration
public class GridClientConfiguration {

    @Bean(destroyMethod = "dispose")
    public ConnectionFactory gridConnectionFactory(
            @Value("${app.grid.url}") String url) {
        return ConnectionFactory.fromUrl(url);
    }
}
```

```yaml
app:
  grid:
    url: grid://app:secret@127.0.0.1:15432,127.0.0.1:15433/public?maxTxContexts=64&connectTimeoutMs=1000
spring:
  threads:
    virtual:
      enabled: true
```

Rules for the client side:

- Do **not** enable `grid.sql-server.enabled` and do not drag `grid-server-core` into every service — the engine must not start inside the application process.
- One factory per process (or per peer), not a factory per request.
- Replica reads: client URL needs `readEndpoints`; the **server** also needs `grid.replication.ha.replica-reads-enabled: true` (library default off; starter `primary`/`replica` profiles turn it on). See [replica reads](../configure-and-operate/operations/replica-reads.md).
- Do not block reactive chains inside a service; `.block()` belongs at the application boundary only. See [Java client](java-client.md).

## A local pair and beyond

Bringing up 1+1 on one machine — [starting a cluster](../getting-started/start-cluster.md). Compose topologies — [Compose deployment](../configure-and-operate/operations/deploy-compose.md).

**Related:** [Java client](java-client.md), [transactions](transactions.md), [SQL server](../configure-and-operate/configuration/sql-server.md), [durability](../configure-and-operate/configuration/durability.md).
