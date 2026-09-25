# jOOQ DSL (grid-jooq)

Optional **jOOQ** module for building Simplified SQL and executing it over the Grid JDBC client.
It is **not** a second query engine: the server still parses text via ANTLR `SimplifiedSql`.

## When yes / when no

| Need | jOOQ (`grid-jooq`) |
|------|---------------------|
| Build SELECT/INSERT strings in Java without hand concatenation | Yes |
| Type-safe codegen against a live schema | Yes — **in the consumer app** via `JDBCDatabase` |
| Multiplex DataSource for Spring/jOOQ `DataSourceConnectionProvider` | Yes — `GridDataSource` → `SyncConnectionFactory.shared` |
| Hikari as product pool | **No** — breaks multiplex; use `GridDataSource` (Hikari reject lives in JDBC `GridHikariBridgeGuard`) |
| Capacity / p95 load runs | No — JMeter on `grid://` |
| Full foreign SQL dialect “as is” | No — Simplified SQL only |

## Multiplex DataSource (recommended path)

```text
Apps / jOOQ / DBeaver
  └─ GridDataSource  ≡  GridDriver   (same Sync* API)
       └─ SyncConnectionFactory.shared (interned per URL target)
            └─ RemoteConnectionFactory: at least MIN_TCP_CHANNELS (10) TCP channels
                 └─ Connection.close → park idle channel; DataSource.close → release retain
```

TCP caps / idle / `maxTxContexts` live **only** in `SyncConnectionFactory` / `RemoteConnectionFactory` (client default **256**; server channel hard-cap **8**, Boot does not raise from YAML). There is **no** JDBC-level pool registry. Do **not** replace this with Hikari `maximumPoolSize=N`.

`grid-jooq` consumes only `javax.sql` / `java.sql` + `GridSQL` / `GridDSL` — no Sync*/Remote* imports.

## Dependency

```xml
<dependency>
  <groupId>org.genfork</groupId>
  <artifactId>grid-jooq</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

jOOQ version comes from Spring Boot **3.5.14** BOM (`jooq.version` **3.19.x**, currently **3.19.32**).

## API

- `GridSQL` — `SQLDialect.DEFAULT` + Settings (unquoted names, `?` binds). Surface: EQ JOIN (INNER/LEFT/…), ORDER+LIMIT, INSERT/UPSERT/ON CONFLICT, UPDATE/DELETE with WHERE.
  Prefer `UPSERT` or a plain `ON CONFLICT` string — jOOQ `onConflict` under DEFAULT may emit `ON DUPLICATE KEY` (rejected by SimplifiedSql).
- `GridDSL` — render-only, `Connection`, `DataSource` (prefer `GridDataSource`), `ConnectionProvider`, `jdbc:grid://` URL.
- `GridConnectionProvider` — sync JDBC acquire/release for jOOQ.
- JDBC edge (`grid-sql-client`): `GridDataSource` / `GridDriver` → `SyncConnectionFactory.shared` + `open()`; `JdbcSync` maps exceptions only.

## Consumer codegen (app-owned)

Codegen stays in the application (same pattern as fork). Point `jooq-codegen-maven` at a live node:

```xml
<jdbc>
  <driver>org.genfork.grid.jdbc.GridDriver</driver>
  <url>jdbc:grid://user:pass@127.0.0.1:15432/public</url>
</jdbc>
<generator>
  <database>
    <name>org.jooq.meta.jdbc.JDBCDatabase</name>
    <inputSchema>public</inputSchema>
  </database>
</generator>
```

Grid supplies `information_schema` + JDBC meta (tables/columns/PK/FK/indexes). Library does **not** ship generated `Tables`.

## Example

```java
try (GridDataSource ds = new GridDataSource("jdbc:grid://u:p@127.0.0.1:15432/public")) {
  DSLContext dsl = GridDSL.using(ds);
  dsl.insertInto(table("accounts"))
     .columns(field("id"), field("balance"))
     .values(1, 100)
     .execute();
}
```

See `examples/examples-jooq` and `grid-jooq/README.md`.