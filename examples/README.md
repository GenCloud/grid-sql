# Examples

Runnable **`grid-sql-client`** cases — **reactive** (`ConnectionFactory` / `grid://`) and **JDBC tooling** (`GridDriver` / `jdbc:grid://`) — plus local HA compose labs.

RU: [README.ru.md](README.ru.md)

## Prerequisites

A live SQL port:

```powershell
mvn -pl grid-sql-server-starter -am package -DskipTests
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=capacity
# → grid://@127.0.0.1:15432/public
# → jdbc:grid://@127.0.0.1:15432/public
```

Or compose (`examples/compose/1dc-n2`) for replica / HA demos.

Override with env `GRID_URL` (plain `grid://…`; JDBC examples prepend `jdbc:` via `ExampleSupport.jdbcUrl()`).

## Build

```powershell
mvn -pl examples/examples-jdbc-dml -am package -DskipTests
mvn -pl examples/examples-jdbc-dml exec:java
```

## Reactive modules (`grid://`)

| Module | Focus |
|--------|--------|
| `examples-warmup` | `warmup()` + `obtain()` |
| `examples-connect` / `session` / `ha-url` / `replica-reads` | bootstrap & routing |
| `examples-autocommit` / `tx` / `batch` / `prepare` / `savepoints` / `parallel-tx` / `streaming` / `for-update` | TX & statements |
| `examples-dml` / `indexes` / `join-agg` / `explain` / `pin` | SQL dialect |

**Note:** v1 UPDATE must not mix RMW (`total = total + 10`) and literal SET in one statement — see `examples-dml`.

## JDBC modules (`jdbc:grid://`)

Sync tooling API in the same artifact (`org.genfork.grid.jdbc.GridDriver`). Prefer reactive for capacity / JMeter.

| Module | Focus | Run |
|--------|--------|-----|
| `examples-jdbc-connect` | DriverManager, isValid, metadata | `mvn -pl examples/examples-jdbc-connect exec:java` |
| `examples-jdbc-dml` | Statement / PreparedStatement / ResultSet | `mvn -pl examples/examples-jdbc-dml exec:java` |
| `examples-jdbc-tx` | setAutoCommit / commit / rollback | `mvn -pl examples/examples-jdbc-tx exec:java` |
| `examples-jdbc-savepoints` | setSavepoint / rollback / release | `mvn -pl examples/examples-jdbc-savepoints exec:java` |
| `examples-jdbc-batch` | Statement + PreparedStatement batch | `mvn -pl examples/examples-jdbc-batch exec:java` |
| `examples-jdbc-session` | setSchema / fetchSize / URL options | `mvn -pl examples/examples-jdbc-session exec:java` |

Shared: `examples-common` (`ExampleSupport.run` / `runJdbc`). Docs: [JDBC client](../docs/en/develop/jdbc-tooling.md), [Java client](../docs/en/develop/java-client.md).

## Compose labs

See [`compose/`](compose/) — image via [`scripts/build-sql-image.ps1`](scripts/build-sql-image.ps1).