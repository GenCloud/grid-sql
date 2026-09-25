# API reference

Product SPI lives in **`grid-sql-client`** (apps) and **`grid-server-core`** (engine / catalog on server nodes). Wire protocol is custom Grid little-endian frames (`grid://` / `jdbc:grid://`) — not JDBC/R2DBC and not a foreign SQL wire.

## Client lifecycle

`ConnectionFactory` (one per process) → `obtain()` → `Connection` (one TCP) → `begin()` / autocommit `Statement` → `TxContext` when needed → `dispose()` the factory on shutdown.

| Failure | Likely cause |
|---------|--------------|
| AUTH / connect reject | Wrong user/pass or node not ready yet |
| `bad frameLen` | Client hit a replication port (**5615**/**5616**), not SQL |
| `maxTxContexts=N exhausted` (wire code 5) | More than eight open sessions on one TCP (server hard-cap **8**); Boot does not raise it from YAML — open another `Connection` or close idle `TxContext`s ([SQL server](../configure-and-operate/configuration/sql-server.md)) |
| Stale / `applyLagStale` on replica read | Catch-up lag; `FAIL_CLOSED` — wait or read the writer ([replica reads](../configure-and-operate/operations/replica-reads.md)) |
| Write reject after role promotion | Client did not `rediscoverWriter()` — [promote](../configure-and-operate/operations/ha-promote.md) |
| Reject on `regionEpoch` / dual writers | Write URL points at Hold/Witness, or client rotates hosts without rediscover |
| TX / DML on a read URL | `readEndpoints` / `READ_REPLICA` are SELECT/EXPLAIN only; writes always go to the writer |
| Health readiness DOWN at start | ORCHID not synced yet — expected; do not send traffic |

## Client SPI (`grid-sql-client`)

| Type | Role |
|------|------|
| `ConnectionFactory` | Open connections; `RemoteConnectionFactory`, `RoutingConnectionFactory`; call `dispose()` on shutdown |
| `Connection` | One TCP transport; multiplex sessions |
| `TxContext` | Independent TX + prepare handle (`begin` / `commit` / `rollback`) |
| `Statement` | Bound SQL; `execute` / `executeUpdate`; returns client `Result` |
| `Result` / `Row` | Client SPI row set or update count for a fetch window |
| `SqlResult` | Engine / commons edge type inside the server — not what apps hold from `Statement` |
| `ServerMeta` | Sticky HA metadata (`writerEligible`, `promoteHint`, `regionEpoch`, …); `lastServerMeta()` on the factory |
| `PreparedHandle` | Named PREPARE on the session (`prepare` / `bind` / `execute` / `deallocate`) |

### ConnectionFactory

```java
ConnectionFactory f = new RemoteConnectionFactory(
    "127.0.0.1", 15432, "user", "pass", 32);
Mono<Connection> c = f.obtain();
// on writer loss after promote: ((RemoteConnectionFactory) f).rediscoverWriter();
// f.dispose();
```

URL form: `grid://user:pass@host:15432[,host:15433]/schema?...`.

| URL option | Meaning |
|------------|---------|
| `maxTxContexts` | Client soft cap on parallel TX / sessions on one TCP (default **256**); server hard-cap **8** |
| `readEndpoints` / `readPreference` / `staleReadPolicy` | SELECT/EXPLAIN routing; stale = `FAIL_CLOSED` |
| `retryMode` / `maxRetries` / `retryDelayMs` | Connect retry (`OFF` / `FIXED` / `EXPONENTIAL`); not a substitute for `rediscoverWriter()` |
| `minConnections` / `maxConnections` | Warm-up and TCP pool ceiling on the factory |
| `fetchWindow` | Rows per streaming FETCH |

HA helpers on `RemoteConnectionFactory`: `rediscoverWriter()`, `lastServerMeta()`. Full URL table: [java-client](../develop/java-client.md).

### TxContext / Statement

- Autocommit: `connection.createStatement(sql).executeUpdate()`
- TX: `connection.begin()` → statements on `TxContext` → `commit()` / `rollback()`
- Parallel TX = N `begin()` on one `Connection` (client `maxTxContexts`; server stops at **8**)

See also: [java-client](../develop/java-client.md), [transactions](../develop/transactions.md).

## Server SPI (`grid-server-core`)

| Type | Role |
|------|------|
| `SqlEngine` | Parse (ANTLR) + execute; returns `SqlResult` / update counts |
| `TableCatalog` / `TableSchema` | DDL-backed schema; domain = table name |
| `SqlServer` / `SqlServerRuntime` | TCP SQL listener + runtime wiring |
| `TableStore` | Sharded store + indexes façade |

Apps should **not** embed `SqlEngine` remotely — talk `grid://` to a starter/node. Boot wiring: [spring-boot](../develop/spring-boot.md).

TCP listen session cap is **8** open contexts per channel (Boot does not raise it from `grid.sql.max-tx-contexts`). Privilege catalog file: `{grid.sql.data-dir}/catalog/privileges.meta` — [security](../configure-and-operate/operations/security.md), [SQL server](../configure-and-operate/configuration/sql-server.md).

## JDBC client

JDBC in `grid-sql-client` (`jdbc:grid://`, package `org.genfork.grid.jdbc`) is a stable sync API alongside reactive: [JDBC client](../develop/jdbc-tooling.md).

## Ports

SQL **15432** / **15433** · replication **5615** / **5616**.

**Related:** [sql fundamentals](../sql/fundamentals.md), [positioning](../getting-started/positioning.md).
