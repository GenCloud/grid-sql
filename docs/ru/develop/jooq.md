# jOOQ DSL (grid-jooq)

Опциональный модуль **jOOQ** для сборки Simplified SQL и исполнения через JDBC-клиент Grid.
Это **не** второй движок запросов: сервер по-прежнему парсит текст через ANTLR `SimplifiedSql`.

## Когда да / когда нет

| Нужно | jOOQ (`grid-jooq`) |
|-------|---------------------|
| Собрать SELECT/INSERT строкой в Java без ручной склейки | Да |
| Type-safe codegen по живой схеме | Да — **в приложении-потребителе** через `JDBCDatabase` |
| DataSource с мультиплексом для Spring/jOOQ `DataSourceConnectionProvider` | Да — `GridDataSource` → `SyncConnectionFactory.shared` |
| Hikari как пул соединений приложения | **Нет** — ломает мультиплекс; используйте `GridDataSource` (reject Hikari — в JDBC `GridHikariBridgeGuard`) |
| Замер ёмкости / p95 | Нет — JMeter на `grid://` |
| Полный чужой SQL-диалект «как есть» | Нет — только Simplified SQL |

## DataSource с мультиплексом (рекомендуемый путь)

```text
Приложения / jOOQ / DBeaver
  └─ GridDataSource  ≡  GridDriver   (один Sync* API)
       └─ SyncConnectionFactory.shared (intern на URL target)
            └─ RemoteConnectionFactory: минимум TCP-каналов ≥ MIN_TCP_CHANNELS (10)
                 └─ Connection.close = park; DataSource.close = release retain
```

Лимиты TCP / idle / `maxTxContexts` — **только** в Sync*/`RemoteConnectionFactory`. Отдельного JDBC pool/registry нет. **Не** подменяйте Hikari `maximumPoolSize=N`.

`grid-jooq` использует только `javax.sql` / `java.sql` + `GridSQL` / `GridDSL` — без импортов Sync*/Remote*.

## Зависимость

```xml
<dependency>
  <groupId>org.genfork</groupId>
  <artifactId>grid-jooq</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

Версия jOOQ из Spring Boot **3.5.14** BOM (`jooq.version` **3.19.x**, сейчас **3.19.32**).

## API

- `GridSQL` — `SQLDialect.DEFAULT` + Settings. Поверхность: EQ JOIN (INNER/LEFT/…), ORDER+LIMIT, INSERT/UPSERT/ON CONFLICT, UPDATE/DELETE с WHERE.
  Предпочитайте `UPSERT` или plain `ON CONFLICT` — jOOQ `onConflict` при DEFAULT может выдать `ON DUPLICATE KEY` (SimplifiedSql отклонит).
- `GridDSL` — render-only, `Connection`, `DataSource` (предпочтительно `GridDataSource`), `ConnectionProvider`, URL `jdbc:grid://`.
- `GridConnectionProvider` — sync acquire/release для jOOQ.
- Граница JDBC (`grid-sql-client`): `GridDataSource` / `GridDriver` → `SyncConnectionFactory.shared` + `open()`; `JdbcSync` только маппит исключения.

## Codegen у потребителя

Codegen остаётся в приложении (как в fork). `jooq-codegen-maven` + живой узел:

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

Grid даёт `information_schema` + JDBC meta (tables/columns/PK/FK/indexes). Библиотека **не** поставляет сгенерированные `Tables`.

## Пример

```java
try (GridDataSource ds = new GridDataSource("jdbc:grid://u:p@127.0.0.1:15432/public")) {
  DSLContext dsl = GridDSL.using(ds);
  dsl.insertInto(table("accounts"))
     .columns(field("id"), field("balance"))
     .values(1, 100)
     .execute();
}
```

См. `examples/examples-jooq` и `grid-jooq/README.md`.