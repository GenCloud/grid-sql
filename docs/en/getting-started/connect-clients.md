# Connect clients

Applications, the console, and IDE tools all reach a running node over the same protocol. What differs is the API you use to speak it.

| Who | What | URL |
|-----|------|-----|
| Application or service (reactive) | `ConnectionFactory` in `grid-sql-client` | `grid://…` |
| Application or service (synchronous) | JDBC in `grid-sql-client` (`org.genfork.grid.jdbc`) | `jdbc:grid://…` |
| Engineer at a console | SQL CLI | `grid://…` |
| Engineer in an IDE (DBeaver) | JDBC, the same driver | `jdbc:grid://…` |

Default ports: SQL **15432** on the primary and **15433** on the replica. Ports **5615** and **5616** carry the replication transport — a different protocol that clients never connect to.

## Applications

Depend on `grid-sql-client` alone; the engine is not pulled into the application process.

```xml
<dependency>
  <groupId>org.genfork</groupId>
  <artifactId>grid-sql-client</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

```java
ConnectionFactory factory = ConnectionFactory.fromUrl(
        "grid://app:secret@127.0.0.1:15432,127.0.0.1:15433/public"
                + "?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2");

Mono<Connection> connection = factory.obtain();
```

### URL shape

```
grid://<user>:<password>@<host>:<port>[,<host>:<port>...]/<schema>?<options>
```

Several hosts in the authority are HA candidates, not write load balancing. The client sticks to a writer-eligible node and moves only on a server hint (`ServerMeta`, `PROMOTE_NOTIFY`) — see [promote a node](../configure-and-operate/operations/ha-promote.md).

Common options:

| Option | Default | Meaning |
|--------|---------|---------|
| `maxTxContexts` | `256` | Soft cap on concurrent transactions over one socket |
| `minConnections` | `1` | TCP connections opened on warm-up or first `obtain` |
| `maxConnections` | `1` | Upper bound on TCP sockets in the factory |
| `connectTimeoutMs` | `5000` | Connect timeout |
| `execTimeoutMs` | `0` (off) | Statement execution timeout |
| `retryMode` / `maxRetries` / `retryDelayMs` | `OFF` / `0` / `200` | Connect retry: `OFF` / `FIXED` / `EXPONENTIAL` |
| `fetchWindow` | `64` | Rows per streaming window |
| `timezone` | `UTC` | Zone applied to temporal types |
| `readEndpoints` / `readPreference` / `staleReadPolicy` | — / `PRIMARY` / `FAIL_CLOSED` | Replica routing; see [replica reads](../configure-and-operate/operations/replica-reads.md) |

Full list (including HA helpers): [Java client](../develop/java-client.md). JDBC and Sync without JDBC: [JDBC client](../develop/jdbc-tooling.md). Writer hand-off: [promote](../configure-and-operate/operations/ha-promote.md).

### One connection, many transactions

A single TCP socket multiplexes independent transactions: every `connection.begin()` returns its own `TxContext` with its own dirty buffer. A "one transaction per socket" pool is neither required nor useful here — the ceiling is `maxTxContexts`. Details: [transactions](../develop/transactions.md).

### Replica reads

```
grid://app:secret@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433&readPreference=REPLICA
```

With `readEndpoints` in the URL, `ConnectionFactory.fromUrl` returns a routing connection: autocommit `SELECT` and `EXPLAIN` go to a synced replica, while writes, transactions, DDL, `PREPARE`, and `FOR UPDATE` stay on the writer. You do not need to build two factories by hand.

Reading your own just-written rows through a replica is not guaranteed. When lag exceeds the allowed threshold the replica rejects the read and the client rotates the endpoint. Details: [replica reads](../configure-and-operate/operations/replica-reads.md).

## Reactive and JDBC

Both APIs live in `grid-sql-client` and speak the same protocol. Reactive (`grid://`, `ConnectionFactory`) is the non-blocking Reactor path; JDBC (`jdbc:grid://`) is the stable synchronous path for DataSource/DAO stacks and IDEs. Choose by application stack. Capacity and p95 runs use JMeter over `grid://` only, because synchronous calls skew latency.

## Console

For one-off queries and liveness checks, use the SQL CLI over the same `grid://` URL: [SQL CLI](../tools/sql-cli.md).

## IDE and debugging

DBeaver and IntelliJ Database use the same JDBC driver (`jdbc:grid://127.0.0.1:15432/public`, class `org.genfork.grid.jdbc.GridDriver`). JDK 25 is required, including for DBeaver itself: [JDBC client](../develop/jdbc-tooling.md).

Drive load runs with JMeter over `grid://`, not JDBC: [load and SLO](../tools/jmeter-load-slo.md).

## If it will not connect

| Symptom | Cause |
|---------|-------|
| `bad frameLen …` | Connected to a replication port (**5615** / **5616**) instead of SQL (**15432**) |
| Connection refused | `grid.sql-server.enabled` is off on the node |
| Readiness DOWN, or connect hangs | ORCHID is not synced yet — expected at start with replication; do not send traffic |
| Multi-host URL and writes "jump" between nodes | Multi-host is an HA candidate list, **not** write balancing; pin via `writerEligible` / `PROMOTE_NOTIFY` |
| `class file version 69.0` in DBeaver | DBeaver is not running on JDK 25 |
| `readPreference=REPLICA requires readEndpoints` | A read preference was set without a replica list |
| `REPLICA_READ_STALE` | Replica lag is above `max-stale-lag`; the client will rotate the endpoint |

**Related:** [Java client](../develop/java-client.md), [transactions](../develop/transactions.md), [production checklist](production-checklist.md), [API reference](../api-reference/README.md).
