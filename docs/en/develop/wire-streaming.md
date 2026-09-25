# Streaming results

A large result set does not arrive as a single response. Client and server work in windows: the server delivers a batch of rows and stops until the client asks for the next one. This is built into the product protocol, not layered on top like a JDBC cursor.

## What it looks like on the wire

The protocol is length-prefixed little-endian frames with its own opcodes. For one query the sequence is:

| Frame | Direction | Meaning |
|-------|-----------|---------|
| `EXEC` | client → server | SQL plus parameters, with its own `requestId` |
| `ROW_DESC` | server → client | Column description; the client gets a `Result` with metadata |
| `ROW_DATA` | server → client | Rows of the current window |
| `FETCH(n)` | client → server | Request the next `n` rows for the same `requestId` |
| `EXEC_DONE` | server → client | The result set is finished, the portal is closed |
| `CANCEL` | client → server | Drop the portal and stop delivery |
| `ERROR` | server → client | Execution error |

The server keeps open portals in `SqlExecHandler`, keyed by the `requestId` of the originating `EXEC` (or `BATCH_EXEC`). The first window goes out immediately after `ROW_DESC`; after that the server waits for `FETCH`. `CANCEL` is idempotent: once the portal has drained, or on a repeated call, it is simply acknowledged, and no entry leaks.

The maximum frame size is 16 MiB. That limits a single frame, not the result set: rows travel in windows.

## Window size

| Level | Value |
|-------|-------|
| Server's first window | 64 rows |
| Subsequent `FETCH` | `min(requested Reactor demand, fetchWindow)` |
| Default `fetchWindow` | 64 |
| Per-connection setting | URL option `?fetchWindow=256` |
| Per-statement setting | `statement.fetchWindow(256)` |

So the window is a **ceiling**, not a fixed size: if the subscriber asked for less, the client asks for less too. Reactor backpressure translates directly into the `FETCH` size.

```java
Flux<Order> orders = connection
        .createStatement("SELECT id, customer_id, total FROM orders WHERE total > ?")
        .bind(0, 1000L)
        .fetchWindow(512)
        .execute()
        .flatMap(result -> result.map((row, meta) -> new Order(
                row.get("id", Long.class),
                row.get("customer_id", Long.class),
                row.get("total", Long.class))));
```

How to pick a window:

- Small rows, many of them — a larger window (256…1024): fewer round trips.
- Wide rows or large BLOBs — a smaller window: otherwise a single window bloats in memory and on the channel.
- If the consumer is slow (writing into another system), do not chase a large window: the rows will just sit in the client buffer.

## Cancellation

```java
Flux<Order> firstPage = orders.take(100);
```

`take`, `timeout`, an upstream cancelled subscription — all of them produce `CANCEL` on the wire: the server drops the portal, and the remainder of the result set is never computed or encoded. Calling `Result.cancel()` directly does the same; on a stream that never started or already finished it is a no-op.

The practical meaning: "the first N rows of a huge result set" costs exactly N rows of server work, not a full materialization.

## Common mistakes

| Mistake | What happens |
|---------|----------------|
| Client never sends `FETCH` after the first window | Portal hangs; server waits; the connection stays busy |
| Window too large on wide rows | Memory spike and frames approaching the 16 MiB limit |
| Ignoring subscription cancel | Server keeps computing the remainder after the client has left |
| Confusing the `requestId` correlator with a TCP pool | Opening extra sockets instead of several `TxContext`s on one |

## Request correlation, not a connection pool

Several requests live concurrently on one TCP socket. The client's `SqlClientInboundHandler` (`org.genfork.grid.sql.client.transport`) keeps a `requestId → pending exchange` map and dispatches inbound frames through it. That map is a **correlator**, not a pool of idle connections and not a set of "warmed slots". Reactive SPI and Sync* (`JdbcSync` / `SyncAwait`) share this transport package.

Two consequences follow:

- `maxTxContexts` on the client is a soft cap on logical sessions over a socket (default **256**); the server hard-caps at **8** per TCP. It is not a TCP connection pool size.
- Parallel queries on one connection are normal: each has its own `requestId` and its own portal.

`BATCH_EXEC` follows the same scheme: N statements per RTT, results arriving in order on one `requestId`, and the active statement inside the batch uses the same `FETCH` / `CANCEL` path.

## What happens on the server

`SqlEngine` executes the query, and the Netty layer encodes cells with LE tags. Inside the engine rows live as bytes: keys and values in filters, joins and indexes are `byte[]` / `WireSpan` over the binary record. Decoding into Java objects happens only at the very boundary — when the result row is assembled for the client.

That is why you should not ask the engine for "the entire table in application memory": streaming is cheaper on both sides.

## Measurements

The compare suite contains a `SELECT stream consume` JMH track (µs/op) — it measures streaming overhead, not TPS. Throughput is measured separately with the load generator: [capacity and SLO](../performance/capacity-slo.md), [results summary](../performance/results.md).

**Related:** [Java client](java-client.md), [transactions](transactions.md), [SQL fundamentals](../sql/fundamentals.md), [EXPLAIN and AQE](../sql/explain-and-aqe.md).
