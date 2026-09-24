# Java client

Applications talk to Grid through the **`grid-sql-client`** module. The library opens TCP to a node's SQL port on the product protocol (length-prefixed little-endian frames) and offers **two** equal APIs: reactive (`ConnectionFactory` / Reactor) and synchronous JDBC (`jdbc:grid://`).

The type chain is simple:

| Type | What it is |
|------|------------|
| `ConnectionFactory` | Reactive entry point. Owns the Netty event loop group and the TCP socket pool |
| `Connection` | Transport: one socket carrying multiplexed logical sessions |
| `TxContext` | An independent transaction on that transport |
| `Statement` | One SQL request: `bind(...)`, `execute()`, `executeUpdate()`, `fetchOne()` |
| `Result` / `Row` | The outcome: either a row set or a count of changed rows |

The JDBC client (`org.genfork.grid.jdbc`) is the stable sync entry in the same module: [JDBC client](jdbc-tooling.md).

## Dependency

```xml
<dependency>
  <groupId>org.genfork</groupId>
  <artifactId>grid-sql-client</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

The module pulls in `grid-commons` (shared wire types) and `grid-sql-antlr` (SQL classification for read routing). Server classes (`SqlEngine`, `TableStore`, replication) are not included — an application does not need them.

## Connecting

The recommended path is a URL. `ConnectionFactory.fromUrl` parses the authority, schema and options and decides which implementation to return.

```java
ConnectionFactory factory = ConnectionFactory.fromUrl(
        "grid://app:secret@127.0.0.1:15432,127.0.0.1:15433/public"
                + "?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2");

Mono<Connection> connection = factory.obtain();
```

The programmatic variant, when there is nowhere to assemble a URL:

```java
ConnectionFactory factory = new RemoteConnectionFactory(
        "127.0.0.1", 15432, "app", "secret", /* maxTxContexts */ 32);
```

Create the factory **once per process** (or one per peer) and reuse it; call `dispose()` when the application stops.

**Warmup (optional preheat):** when the URL has `warmup=true` (README / `SqlCli`) or you construct `RemoteConnectionFactory(..., warmup=true)`, call `factory.warmup()` to open `minConnections` TCP sockets into the idle pool (fail-fast on connect/AUTH). The first `obtain()` also fills the min pool if needed — there is no must-warmup gate. Demo: [`examples/examples-warmup`](../../../examples/examples-warmup).

### URL syntax

```
grid://<user>:<password>@<host>:<port>[,<host>:<port>...]/<schema>?<options>
```

| Option | Default | Meaning |
|--------|---------|---------|
| `minConnections` | `1` | TCP sockets opened on warmup / first `obtain` (clamped to `maxConnections`) |
| `maxConnections` | `1` | Upper bound of TCP sockets in the factory pool |
| `maxTxContexts` | `256` | Soft cap on concurrent logical sessions over one socket (`0` — unlimited) |
| `connectTimeoutMs` | `5000` | Connection establishment timeout |
| `execTimeoutMs` | `0` (off) | Statement execution timeout |
| `readTimeoutMs` / `writeTimeoutMs` | `0` (off) | Channel timeouts |
| `retryMode` | `OFF` | `OFF` / `FIXED` / `EXPONENTIAL` — retry connect when an endpoint refuses (`FIXED` = constant `retryDelayMs`; `EXPONENTIAL` doubles the delay each attempt from `retryDelayMs`) |
| `maxRetries` / `retryDelayMs` | `0` / `200` | Retry count and base delay |
| `timezone` | `UTC` | Zone for temporal types in binds and results |
| `fetchWindow` | `64` | Rows per FETCH during streaming delivery |
| `readEndpoints` | — | Replica list for reads; enables routing |
| `readPreference` | `PRIMARY` | `PRIMARY` / `REPLICA`; `REPLICA` requires `readEndpoints` |
| `staleReadPolicy` | `FAIL_CLOSED` | On replica lag above the server gate — refuse the read (only value in v1); see [replica reads](../configure-and-operate/operations/replica-reads.md) |
| `maxReadConnections` | `1` | Sockets in the read pool |
| `warmup` | `false` | If `true`, explicit `warmup()` preheats `minConnections` (does not gate `obtain`) |

Several hosts in the authority are **not** write load balancing but a candidate list: the client sticks to a writer-eligible node and changes it only on `ServerMeta` / `PROMOTE_NOTIFY`. More in [node promotion](../configure-and-operate/operations/ha-promote.md).

### Writer change in the application

After failover, do **not** rotate the next host in the URL by hand.

| Signal | What the app should do |
|--------|------------------------|
| `PROMOTE_NOTIFY` / updated `ServerMeta` on the live channel | Prefer the promote hint; the factory may call `rediscoverWriter()` for you |
| Writes still fail / stick to a dead host | Call `rediscoverWriter()` on `RemoteConnectionFactory` (returns a fresh `Connection`); check `lastServerMeta()` / `connection.serverMeta()` for `writerEligible`, `promoteHint`, `regionEpoch` |

Connect retries (`retryMode`) are not a substitute for rediscover after role change. Procedure: [promote](../configure-and-operate/operations/ha-promote.md).

## Autocommit

The most common mode: every request is its own short transaction on the server.

```java
Mono<Long> updated = connection
        .createStatement("UPSERT INTO accounts (id, balance) VALUES (?, ?)")
        .bind(0, 1L)
        .bind(1, 100L)
        .executeUpdate();

Flux<String> names = connection
        .createStatement("SELECT name FROM accounts WHERE balance > ?")
        .bind(0, 0L)
        .execute()
        .flatMap(result -> result.map((row, meta) -> row.get("name", String.class)));
```

Several statements in one RTT — `connection.executeBatch(List.of(...))`. In autocommit each statement commits on its own; there is no shared transaction around them.

## Transactions

```java
Mono<Void> transfer = Mono.usingWhen(
        connection.begin(),
        tx -> tx.createStatement("UPDATE accounts SET balance = balance - 10 WHERE id = 1")
                .executeUpdate()
                .then(tx.createStatement("UPDATE accounts SET balance = balance + 10 WHERE id = 2")
                        .executeUpdate())
                .then(tx.commit()),
        TxContext::rollback);
```

`Mono.usingWhen` is not decoration here: it guarantees that on an error or a cancelled subscription the transaction is closed, and it does so without blocking. Model details in [transactions](transactions.md).

### PREPARE

```java
Mono<Long> once = connection.prepare("upd_bal",
                "UPDATE accounts SET balance = ? WHERE id = ?")
        .flatMapMany(h -> h.bind(0, 50L).bind(1, 1L).execute()
                .concatWith(Flux.defer(h::deallocate)))
        .then(Mono.just(1L));
```

The prepared name lives on the session (`TxContext` / connection). Another TCP session does not see it. Prefer `deallocate` when finished; closing the connection drops all prepares for that session.

### Session helpers

| Method | Role |
|--------|------|
| `connection.pin(table, key[, ttlMs[, qos]])` / `unpin` | Soft overlay pin — [overlay PIN](../configure-and-operate/configuration/overlay-pin.md) |
| `connection.setSchema(schema)` | `SET SCHEMA` for the session |
| `connection.setTimezone(zoneId)` | Session timezone (remote connection) |

### About `.block()`

`Mono` and `Flux` in this API are lazy: until something subscribes, nothing has been sent to the server.

`.block()` is acceptable **only at the application's sync boundary** — `main`, a CLI, a JUnit/JMH harness, a dedicated platform scheduler thread. Inside library code, reactive pipelines, the Netty event loop, or on a virtual-thread carrier doing long work, blocking is forbidden: it stalls the event loop or pins the carrier, and that degrades the whole process, not one request.

```java
// application boundary — this is fine
public static void main(String[] args) {
    final Long rows = connection.createStatement("SELECT 1").executeUpdate().block();
}

// inside a service — not allowed
public void handle() {
    connection.createStatement("...").executeUpdate().block(); // stalls the EL / pins the carrier
}
```

Instead of blocking inside a service, return `Mono` / `Flux` outward and compose with `flatMap`, `then`, `usingWhen`, `doFinally`.

## ReactiveSqlOps

Package `org.genfork.grid.sql.client.ops` provides thin helpers over the same SPI — bind-all, `executeUpdate`, `fetchOne` / `fetchList` / `fetchExists`, ordered multi-step updates (`BoundSql`), and a `RowMapper`. They do **not** own transaction lifecycle (`begin` / `commit` / `close` stay with the caller). On the explicit sync edge (`main`, CLI, tests), use `SyncConnection` / `SyncTxContext` directly (or SyncAwait) rather than a separate ops façade.

| Helper | Use when |
|--------|----------|
| `ReactiveSqlOps` | Normal app path: returns `Mono` / `Flux`, no `.block()`, no SyncAwait |

```java
// reactive — compose in the pipeline
Mono<Optional<String>> name = ReactiveSqlOps.fetchOptional(
        tx, "SELECT name FROM accounts WHERE id = ?", row -> row.get(0, String.class), id);
```

## Parallel transactions

`connection.begin()` can be called several times: each `TxContext` is an independent transaction with its own dirty buffer and its own prepare handle, and they all live on **one** TCP socket.

```java
Mono<Void> parallel = Mono.zip(
        runTx(connection, "UPDATE t SET v = v + 1 WHERE id = 1"),
        runTx(connection, "UPDATE t SET v = v + 1 WHERE id = 2")
).then();
```

This is a fundamentally different model from a JDBC connection pool: you do not need `maxTxContexts` sockets, you need one socket and `maxTxContexts` logical sessions. The cap is soft — it bounds `SESSION_OPEN`, not the number of application threads.

## Replica reads

If the URL specifies `readEndpoints`, `ConnectionFactory.fromUrl` returns a routing connection: autocommit `SELECT` / `EXPLAIN` go to the least loaded synced replica, while writes, transactions, DDL, `PREPARE` and `FOR UPDATE` go to the writer. Classification is done by the shared ANTLR classifier, identical on client and server.

```
grid://app:secret@primary:15432/public?readEndpoints=replica-1:15433,replica-2:15434&readPreference=REPLICA&maxReadConnections=2
```

Read-your-writes through a replica without a round trip to the writer is not guaranteed. When the allowed lag is exceeded the replica answers `REPLICA_READ_STALE` and the client switches endpoint. Full coverage of policies, failures and risks — [replica reads](../configure-and-operate/operations/replica-reads.md).

## Streaming large result sets

Results arrive in windows: the client asks for `fetchWindow` rows, the server delivers exactly that many and waits for the next FETCH. The window is set on the URL or on a specific statement — `statement.fetchWindow(256)`. See [wire streaming](wire-streaming.md).

## JDBC client and Sync without JDBC

Package `org.genfork.grid.jdbc` and the `jdbc:grid://` URL are a stable synchronous API on the same protocol as reactive. Sync façade: `JdbcSync` → `SyncAwait` on `SyncExecExchange` / `SyncBatchExchange`. Reactive SPI: `ReactiveExecExchange` / `ReactiveBatchExchange`. Fat jar for IDEs: `mvn -pl grid-sql-client -am package -DskipTests` → `grid-sql-client/target/grid-sql-client-*-dbeaver.jar`.

For a **sync application that does not want JDBC**, use `SyncConnectionFactory.fromUrl(gridUrl)` → `open()` → `SyncConnection` (same URL options; park/close semantics). Details: [JDBC client](jdbc-tooling.md) (§ Sync without JDBC). Capacity load runs use JMeter on `grid://` only — not JDBC.

## Runnable examples

Runnable `grid-sql-client` cases live under [`examples/`](../../../examples/). Optional `factory.warmup()` when `?warmup=true` preheats `minConnections` (fail-fast connect/AUTH) — see `examples-warmup`. Borrow a channel with `factory.obtain()`. Other modules cover connect, session, TX, batch, PREPARE, savepoints, streaming, parallel TX, replica reads, HA URL, DML, indexes, JOIN/agg, EXPLAIN, `FOR UPDATE`, PIN. Point `GRID_URL` at a live SQL port (`capacity` or `examples/compose/1dc-n2`). See [`examples/README.md`](../../../examples/README.md).

**Related:** [transactions](transactions.md), [Spring Boot](spring-boot.md), [wire streaming](wire-streaming.md), [connecting clients](../getting-started/connect-clients.md), [API reference](../api-reference/README.md).
