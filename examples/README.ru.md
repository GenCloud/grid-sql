# Examples

Runnable-кейсы **`grid-sql-client`** — **реактивный** (`ConnectionFactory` / `grid://`) и **JDBC-клиент** (`GridDriver` / `jdbc:grid://`) — плюс локальные HA compose-лабы (не GitHub Actions).

EN: [README.md](README.md)

## Предпосылки

Живой SQL-порт (`capacity` или `examples/compose/1dc-n2`). Env `GRID_URL` — обычный `grid://…`; JDBC-примеры добавляют `jdbc:` через `ExampleSupport.jdbcUrl()`.

## Сборка / запуск

```powershell
mvn -pl examples/examples-jdbc-dml -am package -DskipTests
mvn -pl examples/examples-jdbc-dml exec:java
```

## Реактивный (`grid://`)

Модули `examples-warmup` … `examples-ha-url` / `examples-dml` и т.д. — см. [README.md](README.md).

В одном UPDATE v1 нельзя смешивать RMW и literal SET — в `examples-dml` они разделены.

## JDBC (`jdbc:grid://`)

| Модуль | Фокус |
|--------|--------|
| `examples-jdbc-connect` | DriverManager, isValid, metadata |
| `examples-jdbc-dml` | Statement / PreparedStatement / ResultSet |
| `examples-jdbc-tx` | setAutoCommit / commit / rollback |
| `examples-jdbc-savepoints` | setSavepoint / rollback / release |
| `examples-jdbc-batch` | Statement + PreparedStatement batch |
| `examples-jdbc-session` | setSchema / fetchSize / URL options |

jOOQ: `examples-jooq` — [jOOQ DSL](../docs/ru/develop/jooq.md). Compose: [`compose/`](compose/) — только локальный стенд.
