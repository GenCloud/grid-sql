# grid-jooq

Optional **jOOQ** DSL for Grid **SimplifiedSql**. Not a DB engine: server parse remains ANTLR; product capacity stays on `grid://` `ConnectionFactory`.

## Canon wiring (anti-Hikari)

```text
GridDataSource ≡ GridDriver  — SyncConnectionFactory.shared, floor 10 TCP x maxTxContexts
  -> DataSourceConnectionProvider / GridDSL.using(DataSource)
  -> DefaultDSLContext + GridSQL Settings
```

Hikari N-socket pools are **not** the product path — use `GridDataSource`. TCP multiplex is owned by Sync* only (no JDBC pool registry). This module imports only JDBC + jOOQ + `GridSQL`/`GridDSL`.

## Dependency

```xml
<dependency>
  <groupId>org.genfork</groupId>
  <artifactId>grid-jooq</artifactId>
  <version>1.1.0</version>
</dependency>
```

## Usage

```java
try (GridDataSource ds = new GridDataSource("jdbc:grid://user:pass@127.0.0.1:15432/public")) {
  DSLContext dsl = GridDSL.using(ds);
  // CRUD / render — server parses SimplifiedSql
}
```

## Temporal (`TIMESTAMP` ↔ `LocalDateTime`)

Grid JDBC owns the contract: `setObject(LocalDateTime)` / `getObject(…, LocalDateTime.class)` /
`getTimestamp` (via `SqlTypeCoercion.toLocalDateTime`). jOOQ DEFAULT `LOCALDATETIME` works on that
driver surface — no app Binding / proxy.

## Batch binds / SKIP LOCKED

Public docs: [docs/en/develop/jooq.md](../docs/en/develop/jooq.md) — `GridDialect` capability flags,
`GridDSL.buildBatchStatements` / `executeBatchBound`, and `.forUpdate().skipLocked()` under
`GridSQL.installProduct` (`GridForUpdateSkipLocked`).

## Consumer codegen

Introspect via `JDBCDatabase` + `GridDriver` in the **application** (not library `-Pcodegen` shipping Tables). See [docs/en/develop/jooq.md](../docs/en/develop/jooq.md).

## Tests / JMH

```text
mvn -pl grid-jooq -am test
mvn -pl grid-jooq -am test -Pjmh
```