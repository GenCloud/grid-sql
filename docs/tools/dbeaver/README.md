# DBeaver / IDE — JDBC client (`grid-sql-client`)

Thin JDBC 4.3 driver for tooling (DBeaver, IntelliJ Database). Classes live in `grid-sql-client` package `org.genfork.grid.jdbc` (driver FQCN: `org.genfork.grid.jdbc.GridDriver`). For IDE exploration only. Services use `ConnectionFactory` / `grid://` from the same module — never JDBC as the app product path.

Sync edge: `JdbcSync` → `SyncAwait`. Shared Netty: `org.genfork.grid.sql.client.transport.*`.

## Java runtime (required)

The driver and shaded fat-jar are built for **Java 25** (class file **69**).

DBeaver bundled JRE is often **Java 17/21** (class file 61/65). That produces:

GridDriver has been compiled by a more recent version of the Java Runtime (class file version 69.0), this version only recognizes class file versions up to 65.0

**Fix:** run DBeaver on JDK 25 (same as the grid build).

1. Install JDK 25.
2. Edit dbeaver.ini next to the DBeaver binary (before -vmargs):

```
-vm
C:/path/to/jdk-25/bin
```

Point at the bin directory that contains javaw.exe. Restart DBeaver.

IntelliJ Database: use Project JDK 25.

There is no Java-21 bytecode build of this stack (grid-sql-client is Java 25 --enable-preview).

## Start SQL server (IDE)

- Main: org.genfork.grid.sql.SqlServerMain (module grid-server-core, VM --enable-preview)
- Or Boot: org.genfork.grid.sql.server.GridSqlServerStarter with `grid.sql-server.enabled=true`

Default SQL TCP: **15432**. Replication transport (`bind-port` **5615**) is a different protocol — do not point DBeaver there.

## DBeaver connection

| Field | Value |
|-------|--------|
| Host | `localhost` / `127.0.0.1` |
| Port | **15432** (not 5615) |
| Database/Schema | URL path after hosts = **schema** (e.g. `public`) — not a separate JDBC catalog |
| User / Password | `grid` / `grid` (defaults; override via `grid.sql-server.user`/`password`) |
| URL | `jdbc:grid://grid:grid@127.0.0.1:15432/public` |

### `bad frameLen …`

Means the socket is not speaking SQL LE frames. Almost always wrong port: **5615 = replication**, **15432 = SQL**. Also ensure `grid.sql-server.enabled=true` (profiles like `primary` may leave SQL TCP off).

## Build driver

```powershell
mvn -pl grid-sql-client -am package -DskipTests
```

Use `grid-sql-client/target/grid-sql-client-*-dbeaver.jar` as Custom Driver.
Class: `org.genfork.grid.jdbc.GridDriver`
URL: `jdbc:grid://grid:grid@127.0.0.1:15432/public`

## Capabilities

- Schema tree, SQL Editor, TX
- **Create / Drop schema** from SQL (and Generic DBeaver Create Schema when the menu is available): `CREATE SCHEMA name`, `DROP SCHEMA name` (RESTRICT optional)
- Driver Manager: do **not** enable “Omit schema(s)” / “Omit single schema”
- Multi-statement scripts → ANTLR split → BATCH_EXEC
- `readEndpoints` + `readPreference=REPLICA` → Sync routing (SELECT → replica)
- Scrollable ResultSet + Data Editor (single-table + PK; materialize for IDE)
- Statement.cancel → SyncAwait cancel + wire CANCEL
- Column metadata from SPI RowMetadata / ROW_DESC v2 (JDBC catalog hints only as fallback)
- Unwrap SyncConnection / ServerMeta; pin/unpin; timezone client-info

### Admin / manage menus (important)

Custom Driver uses DBeaver’s Generic navigator only: there is **no** Administration folder and **no** Manage Users / Manage Schemas wizards.

Use SQL instead:

```sql
CREATE SCHEMA my_app;
CREATE USER app WITH PASSWORD 'secret';
GRANT SELECT ON my_app.accounts TO app;
SELECT * FROM information_schema.users;
SELECT * FROM information_schema.roles;
SELECT * FROM information_schema.role_members;
SELECT * FROM information_schema.table_privileges;
```

URL path after the hosts is the **default schema** (`…/my_app` → `Connection.getSchema()` = `my_app`).

## Schemas vs databases

One Grid SQL process = one JDBC catalog (`grid`) with multiple **schemas**. Separate `CREATE DATABASE` catalogs are deferred: same process shares Netty / ThreadService / OpLog, so databases would not reduce contention vs schemas. Split load with separate processes/nodes when needed.

Full guide: [EN jdbc-tooling](../../en/develop/jdbc-tooling.md) / [RU jdbc-tooling](../../ru/develop/jdbc-tooling.md).
