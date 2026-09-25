# SQL server

`SqlServerRuntime` owns the SQL catalog, the engine, and — when the TCP listener is enabled — the `SqlServer` Netty endpoint. Applications never embed it: they connect through `grid-sql-client`, either reactively on `grid://` or through JDBC on `jdbc:grid://`. Both APIs speak the same framed protocol on the same port.

Server side: which keys turn the listener on, how to verify it accepts traffic, and which rejections come from the server rather than the network.

## Enabling the listener

`grid.sql-server.enabled` defaults to `false`, so a plain library embedding exposes no port. Starter profiles `primary`, `replica`, and `capacity` enable it. Client applications must not set this key.

```yaml
grid:
  sql-server:
    enabled: true
    host: 0.0.0.0
    port: 15432
  sql:
    default-shards: 8
    data-dir: ./data-primary/catalog
    lock-wait-timeout-ms: 8000
    prepare-pool-size: 64
```

| Key | Default | Meaning |
|-----|---------|---------|
| `grid.sql-server.enabled` | `false` | Bind the SQL TCP listener |
| `grid.sql-server.host` | `0.0.0.0` | Bind address; restrict it when the host is multi-homed |
| `grid.sql-server.port` | `15432` | SQL port |
| `grid.sql-server.user` / `password` | `grid` / `grid` | Bootstrap master credentials; when the user catalog is empty they are seeded as administrator (full power). Blank both → open-auth (no seed) |
| `grid.sql.default-shards` | `4` | Shard count for tables created without an explicit clause |
| `grid.sql.data-dir` | `./data/catalog` | Catalog root; `catalog/privileges.meta` lives under it (per-node; not shipped by replication) |
| `grid.sql.lock-wait-timeout-ms` | `8000` | Record-lock wait ceiling before `lock wait timeout` |
| `grid.sql.prepare-pool-size` | `64` | Prepared-statement pool per engine |
| `grid.sql.catalog-meta-cache-size` | `256` | Catalog metadata cache entries |
| `grid.sql.timezone` | `UTC` | Default session time zone |
| `grid.sql.recursive-cte-max-depth` | `32` | Recursion ceiling for recursive common table expressions |

`FOR UPDATE` peer locks (when replication is on) use Netty agents built from **replication `peers`**, not from `grid.sql.distributed-peers` — see [replica reads](../operations/replica-reads.md).

`grid.sql.distributed-peers` is a different knob: it enables **read-only SELECT/JOIN fan-out** to remote SQL peers (`DistributedKeyFanOut` in the query executor). Empty list = local-only queries. It does **not** install Dist FOR UPDATE lock agents.

Pick `default-shards` for the largest tables you expect: it sets shard parallelism and cannot be changed for an existing table without recreating it. Eight to sixteen shards is a reasonable starting range on a multi-core host.

## Port layout

| Port | Protocol | Clients |
|-----:|----------|---------|
| 15432 | SQL frames (`grid://`, `jdbc:grid://`) | Applications, DBeaver, load tools |
| 5615 and up | Replication Netty | Nodes only |
| 7777 (starter primary default) | Actuator HTTP | Orchestrator probes, scrapes |
| 7778 (starter `replica` and `capacity`) | Actuator HTTP | Same, on replica or capacity process |

A SQL client pointed at a replication port fails frame decoding with `bad frameLen`. When you co-locate several nodes on one host, give each its own SQL port, its own replication port, and its own Actuator `server.port`.

## Typical profiles

| Profile | SQL port | Role |
|---------|---------:|------|
| `primary` | 15432 | Writer of a local pair |
| `replica` | 15433 | Replica of that pair |
| `capacity` | 15432 | Solo durable node, no replication |

## Concurrent sessions on one connection

One TCP connection carries many logical sessions. The client opens a session per `TxContext`; the server caps how many may be open at once on a single channel and answers `maxTxContexts=N exhausted` (wire error code 5) beyond that.

The two sides have different defaults, and the server side is the one that binds:

| Side | Default | Notes |
|------|---------|-------|
| Server channel (Spring Boot wiring) | `8` | The Boot adapter does not forward `grid.sql.max-tx-contexts` to the listener |
| Client (`grid://`, `RemoteConnectionFactory`) | `256` | URL parameter or constructor argument |

So an application that keeps more than eight transactions open concurrently on one connection will see rejections even though its own limit is higher. Spread the load across additional `Connection` instances — each is its own TCP channel — rather than raising the client value. This is a session cap, not a socket pool: parallel transactions are `TxContext` objects multiplexed over one channel.

## Authentication boundary

The AUTH frame establishes channel identity only. It does not arbitrate commits: write admission and durability are decided by ORCHID and the journal — see [durability](durability.md). Writer discovery happens through wire metadata, not configuration — see [role promotion](../operations/ha-promote.md).

| Catalog state | Behaviour |
|---------------|-----------|
| No users defined | Server seeds master from `grid.sql-server.user`/`password` (defaults `grid`/`grid`) with absolute privileges on all schemas/tables; blank both keeps open-auth |
| Users defined | Credentials are required in the URL; frames without AUTH are rejected with `not authenticated` |

Restrict network access to the SQL port before the first `CREATE USER` — see [security](../operations/security.md).

## Bring-up checklist

1. Start the node and watch for the SQL bind in the log.
2. Query readiness: `curl http://127.0.0.1:7777/health/readiness` with the starter layout, or `/actuator/health/readiness` on the default Spring base path.
3. Confirm `sqlTcp: listening` in the readiness details. With replication enabled, wait for `orchidSynced: true` — readiness stays DOWN until then by design.
4. Connect and run a trivial statement:

```text
grid://grid:grid@127.0.0.1:15432/public
SELECT 1;
```

See [SQL CLI](../../tools/sql-cli.md) for the interactive client.

5. Optionally create application users and grant them narrowly; change the master password in production.
6. Only then point application or load traffic at the node.

## Running without Spring Boot

From an IDE, start `org.genfork.grid.sql.SqlServerMain` with preview features enabled. As a packaged artifact, use the `grid-sql-server-starter` fat jar with one of the profiles above.

## Symptom and action

| Symptom | Cause | Action |
|---------|-------|--------|
| Connection refused on 15432 | Listener disabled or bound to another interface | Set `grid.sql-server.enabled: true`; check `host` |
| `bad frameLen` | Client pointed at a replication port | Use the SQL port |
| Readiness DOWN, `reason: sql_tcp_down` | Listener enabled but not bound | Check port conflicts in the startup log |
| Readiness DOWN, `reason: orchid_not_synced` | Replication not yet synced | Wait; do not send traffic — [replication](replication.md) |
| `AUTH failed` | Credentials do not match the catalog | Fix the URL authority — [security](../operations/security.md) |
| `not authenticated` | Frames sent before AUTH after users exist | Supply credentials in the URL |
| `maxTxContexts=8 exhausted` | More than eight concurrent sessions on one channel | Open an additional `Connection` |
| `lock wait timeout` | Another transaction holds the record lock | Shorten transactions; review `grid.sql.lock-wait-timeout-ms` |
| Two nodes on one host both fail to start Actuator | Both default to port 7777 | Give each its own `server.port` |

## Constraints

- One SQL port per node; there is no separate JDBC port, and AUTH, readiness, and writer discovery are shared by both client APIs.
- Each node needs its own data directory. A cluster-wide share is not supported.
- There is no TLS on the SQL port in the product; terminate it on a proxy in front of the node — [security](../operations/security.md).
- Drive load and latency measurements through the reactive client; JDBC is a supported synchronous API but adds its own blocking layer.

**Related:** [security](../operations/security.md), [Spring Boot](../../develop/spring-boot.md), [Java client](../../develop/java-client.md), [connect clients](../../getting-started/connect-clients.md), [monitoring](../monitoring.md).
