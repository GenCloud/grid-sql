# JDBC client

Stable **synchronous** Grid client in **`grid-sql-client`**, package `org.genfork.grid.jdbc` (driver FQCN: `org.genfork.grid.jdbc.GridDriver`). There is no separate `grid-jdbc` module.

JDBC stands **alongside** the reactive API (`ConnectionFactory` / Reactor): one protocol (little-endian frames on the SQL port), two application entry points. Inside the driver, `GridDriver` and `GridDataSource` share one `SyncConnectionFactory.shared` per URL target; obtain/park go through Sync* only (`obtainStage` + `SyncAwait` / `SyncExecutors`, never `Mono.toFuture`). `JdbcSync` maps throwables to `SQLException` only. TCP caps live in Sync*/`RemoteConnectionFactory` — no JDBC pool registry. **`Connection.close` parks** the channel into the idle pool (Driver also releases one factory retain); **`DataSource.close` / last retain** disposes the shared factory. After TCP death the client refills toward `minConnections` asynchronously. Reactive SPI completes via Reactor Sinks (`ReactiveExecExchange` / `ReactiveBatchExchange`); Sync* uses CF only — shared demux under `org.genfork.grid.sql.client.transport.*`.

Integration tests for the driver live in `grid-server-core` (`GridJdbcIT`).

## When JDBC / when reactive

| Scenario | Use |
|----------|-----|
| Sync JDBC / DataSource / classic DAO service | JDBC (`jdbc:grid://`, `org.genfork.grid.jdbc`) |
| Sync app **without** JDBC types | `SyncConnectionFactory` + `SyncAwait` (same module) |
| Reactor service / many parallel requests without blocking | Reactive: `ConnectionFactory` + `grid://` |
| DBeaver / IntelliJ Database, one-off SQL | JDBC (same driver) |
| Capacity / p95 / JMeter | `grid://` only (reactive) — sync skews latency |

## Sync without JDBC

When the stack is synchronous but you do not want `java.sql.*`, use the Sync façade over the same `grid://` URL:

```java
try (SyncConnectionFactory factory = SyncConnectionFactory.fromUrl(
        "grid://app:secret@127.0.0.1:15432/public")) {
    SyncConnection c = factory.open();
    try {
        long n = c.executeUpdate("UPSERT INTO t (id) VALUES (1)");
    } finally {
        c.close(); // parks the channel into the idle pool
    }
}
```

`fromUrl` shares URL semantics with `ConnectionFactory.fromUrl` (including `readEndpoints`). Obtain waits on `RemoteConnectionFactory.obtainStage()` via `SyncAwait` — never `Mono.toFuture`. On shutdown close the factory (`AutoCloseable`). Reactive path: [Java client](java-client.md).

## Service example

```java
Class.forName("org.genfork.grid.jdbc.GridDriver");
try (Connection c = DriverManager.getConnection(
        "jdbc:grid://user:pass@127.0.0.1:15432/public")) {
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT id FROM t LIMIT 10")) {
        while (rs.next()) {
            // …
        }
    }
}
```

Dependency is the same `grid-sql-client` artifact (or the `*-dbeaver.jar` fat jar for IDEs). Prefer reactive when you need non-blocking parallel exchange on one event loop; prefer JDBC when the application stack is already sync (Spring JDBC, MyBatis, hand-written DAO).

## Runnable examples

Under [`examples/`](../../../examples/): `examples-jdbc-connect`, `examples-jdbc-dml`, `examples-jdbc-tx`, `examples-jdbc-savepoints`, `examples-jdbc-batch`, `examples-jdbc-session`. Same live SQL port as the reactive demos; URL via `GRID_URL` mapped to `jdbc:grid://…`. See [`examples/README.md`](../../../examples/README.md).

## JRE requirement

The driver and its fat jar are built for **Java 25** (class file version 69). DBeaver's bundled JRE is usually 17 or 21, and then connecting shows:

```
GridDriver has been compiled by a more recent version of the Java Runtime
(class file version 69.0), this version only recognizes class file versions up to 65.0
```

The fix is running DBeaver itself on JDK 25. In `dbeaver.ini`, **before** the `-vmargs` line:

```
-vm
C:/path/to/jdk-25/bin
```

The path must point at the `bin` directory containing `javaw.exe`. Restart after editing. In IntelliJ it is enough to set the Project JDK to 25. There is no Java 21 build of this stack: `grid-sql-client` compiles on Java 25 with `--enable-preview`.

## Building the driver

```powershell
mvn -pl grid-sql-client -am package -DskipTests
```

For a Custom Driver, take `grid-sql-client/target/grid-sql-client-*-dbeaver.jar` — it already bundles the transitive dependencies (Maven classifier `dbeaver`).

## Connecting in DBeaver

1. **Database → Driver Manager → New**, add the jar.
2. Class name: `org.genfork.grid.jdbc.GridDriver`
3. URL template / URL: `jdbc:grid://grid:grid@127.0.0.1:15432/public`

| Field | Value |
|-------|-------|
| Host | `127.0.0.1` |
| Port | **15432** (replica — **15433**) |
| Database / Schema | `public` |
| User / Password | Empty catalog — password optional; after the first `CREATE USER` — same credentials as AUTH (see [security](../configure-and-operate/operations/security.md)) |

The URL format is the same as `grid://`, just with a `jdbc:` prefix: `jdbc:grid://user:pass@h1:15432,h2:15433/public`. The path after the hosts is the **default schema** (not a separate database catalog). The `?hosts=` parameter is not supported — multiple hosts go comma-separated in the authority.

The driver strips `jdbc:` and parses with the same URL parser as the reactive client. Entry is **`SyncConnectionFactory.fromUrl` / `shared`** (same product URL semantics as `ConnectionFactory.fromUrl`): when `readEndpoints` + `readPreference=REPLICA` are present, autocommit SELECT/EXPLAIN route to the read pool. Inherited `grid://` options: `maxTxContexts`, `readEndpoints`, `readPreference`, `fetchWindow`, HA host ring. URL details: [connect clients](../getting-started/connect-clients.md).

Example replica URL:

```
jdbc:grid://u:p@127.0.0.1:15432/public?readPreference=REPLICA&readEndpoints=127.0.0.1:15433,127.0.0.1:15434
```

## What works

- Schema tree: catalog, tables, columns, indexes.
- `Connection.getSchema()` reflects the URL path schema (e.g. `…/my_app` → `my_app`).
- Create / drop schemas via SQL (`CREATE SCHEMA` / `DROP SCHEMA`; RESTRICT optional). Prefer not enabling “Omit schema(s)” in the DBeaver Driver Manager.
- Admin via SQL only — Custom Driver has no Administration / Manage Users menu:
  - `CREATE USER` / `DROP USER` / `GRANT` / `REVOKE`
  - `SELECT * FROM information_schema.users|roles|role_members|table_privileges`
- SQL Editor: one-off `SELECT` and DML within the simplified dialect.
- **Multi-statement scripts** (semicolon-separated): ANTLR `script` split → `BATCH_EXEC` (DBeaver script without Bad SQL).
- Transactions, including savepoints. Parallel TX = N JDBC `Connection`s from the same factory (multiplex), not one TX per socket.
- A scrollable `ResultSet` and the Data Editor — for a single table with a primary key (rows materialized for IDE navigation).
- `Statement.cancel()` cancels the in-flight SyncAwait and sends wire `CANCEL`.
- `Statement.setQueryTimeout` → Sync await budget; timezone via `Connection.setClientInfo("timezone", …)` / unwrap `SyncConnection`.
- Unwrap: `SyncConnection`, `ServerMeta`, `SyncConnectionFactory`; helpers `pin` / `unpin` on `GridConnection`.
- Column metadata comes from `RowMetadata` / `ROW_DESC`; JDBC catalog hints are used only as a fallback.
- `isValid()` checks the channel lifecycle instead of round-tripping `SELECT 1`.

## A common error: `bad frameLen …`

It means the socket is not speaking SQL frames. Almost always it is the wrong port: **5615 is the replication transport**, **15432 is SQL**. The other possibility is that SQL TCP is simply not enabled: check `grid.sql-server.enabled: true`.

| Symptom | Cause |
|---------|-------|
| Class file version 69 | DBeaver/IDE on JDK < 25 — see above |
| AUTH / empty catalog | Server expects users but URL has no credentials (or the reverse) |

## What not to do

- Do not measure capacity or SLO through JDBC. Load is driven by JMeter over `grid://`: [load and SLO](../tools/jmeter-load-slo.md).
- `jdbc:grid://` is the same Grid product protocol with a `jdbc:` prefix — not a second wire stack.
- Do not point the client at a replication port (**5615** / **5616**).

If you want a console instead of an IDE, there is the [SQL CLI](../tools/sql-cli.md). Reactive path: [Java client](java-client.md).

**Related:** [Java client](java-client.md), [connecting clients](../getting-started/connect-clients.md), [security](../configure-and-operate/operations/security.md), [SQL server](../configure-and-operate/configuration/sql-server.md), [DBeaver README](../../tools/dbeaver/README.md).
