# Examples

Runnable-кейсы **`grid-sql-client`** — **reactive** (`ConnectionFactory` / `grid://`) и **JDBC tooling** (`GridDriver` / `jdbc:grid://`) — плюс локальные HA compose-лабы.

EN: [README.md](README.md)

## Предпосылки

Живой SQL-порт (`capacity` или `examples/compose/1dc-n2`). Env `GRID_URL` — обычный `grid://…`; JDBC-примеры добавляют `jdbc:` через `ExampleSupport.jdbcUrl()`.

## Сборка / запуск

```powershell
mvn -pl examples/examples-jdbc-dml -am package -DskipTests
mvn -pl examples/examples-jdbc-dml exec:java
```

## Reactive (`grid://`)

Модули `examples-warmup` … `examples-ha-url` / `examples-dml` и т.д. — см. [README.md](README.md).

**Важно:** в одном UPDATE v1 нельзя смешивать RMW и literal SET — в `examples-dml` они разделены.

## JDBC (`jdbc:grid://`)

| Модуль | Фокус |
|--------|--------|
| `examples-jdbc-connect` | DriverManager, isValid, metadata |
| `examples-jdbc-dml` | Statement / PreparedStatement / ResultSet |
| `examples-jdbc-tx` | setAutoCommit / commit / rollback |
| `examples-jdbc-savepoints` | savepoint / rollback / release |
| `examples-jdbc-batch` | batch Statement + PreparedStatement |
| `examples-jdbc-session` | setSchema / fetchSize / URL options |

Доки: [JDBC-клиент](../docs/ru/develop/jdbc-tooling.md), [Java-клиент](../docs/ru/develop/java-client.md).