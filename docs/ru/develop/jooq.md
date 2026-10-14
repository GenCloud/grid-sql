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
  └─ GridDataSource  ≡  GridDriver   (один синхронный API)
       └─ SyncConnectionFactory.shared (общий на целевой URL)
            └─ RemoteConnectionFactory: минимум TCP-каналов ≥ MIN_TCP_CHANNELS (10)
                 └─ Connection.close → канал в пул простоя; DataSource.close → отпустить фабрику
```

Лимиты TCP / простоя / `maxTxContexts` задаются только в `SyncConnectionFactory` / `RemoteConnectionFactory` (клиент по умолчанию **256**; серверный потолок канала **8**, настраивается через `grid.sql-server.max-tx-contexts`). Отдельного пула JDBC нет. **Не** подменяйте Hikari `maximumPoolSize=N`.

`grid-jooq` использует только `javax.sql` / `java.sql` + `GridSQL` / `GridDSL` — без прямых импортов синхронного фасада и `RemoteConnectionFactory`.

## Зависимость

```xml
<dependency>
  <groupId>org.genfork</groupId>
  <artifactId>grid-jooq</artifactId>
  <version>1.1.0</version>
</dependency>
```

Версия jOOQ из Spring Boot **3.5.14** BOM (`jooq.version` **3.19.x**, сейчас **3.19.32**).

## API

- `GridSQL.installProduct(Configuration)` — host `SQLDialect.DEFAULT` + Settings; **fail-fast** на SQLITE/POSTGRES/MYSQL/…. **Не** регистрирует VisitListener / ExecuteListener SQL-rewrite.
- Логический диалект = `GridDialect` (модуль `org.genfork.grid.jooq.dialect`), не enum `SQLDialect.GRID` (в OSS jOOQ закрытый enum).

| Флаг `GridDialect` | Смысл |
|--------------------|-------|
| `supportsOnConflictDoUpdate` | `INSERT … ON CONFLICT (…) DO UPDATE SET …` / `EXCLUDED.col` |
| `supportsOnConflictDoNothing` | `INSERT … ON CONFLICT (…) DO NOTHING` |
| `supportsReturning` | Нативный `INSERT … RETURNING …` (без эмуляции `last_insert_rowid`) |
| `supportsTruncate` | `TRUNCATE TABLE` |
| `supportsForUpdateSkipLocked` | `FOR UPDATE SKIP LOCKED` |
| `supportsIndexedBinds` | Индексированные binds `?` |

- `GridDSL` — factory `DSLContext` + Grid QueryParts:
  - `GridDSL.insertInto(dsl, table).set(…).onConflict(…).doUpdate().set(…)` → `ON CONFLICT … DO UPDATE` (не `ON DUPLICATE KEY`)
  - `onConflictDoNothing` / `insertReturning` / `truncate`
  - `GridDSL.buildBatchStatements(queries)` → слоты SPI `BatchStatement`; `GridDSL.executeBatchBound(dsl, queries)` → один bound `BATCH_EXEC` через `GridConnection`
  - Под `installProduct` jOOQ `.forUpdate().skipLocked()` рендерит `FOR UPDATE SKIP LOCKED` (`GridForUpdateSkipLocked`); только на writer — [транзакции](transactions.md)
- Запрещено в product path: `SQLDialect.SQLITE|POSTGRES|MYSQL`, VisitListener/ExecuteListener strip-rewrite как «диалект».
- `GridConnectionProvider` — sync acquire/release для jOOQ.
- Граница JDBC (`grid-sql-client`): `GridDataSource` / `GridDriver` → `SyncConnectionFactory.shared` + `open()`; `JdbcSync` только маппит исключения. `RETURNING` через `executeUpdate` + `getGeneratedKeys()` — [JDBC-клиент](jdbc-tooling.md).

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
  GridDSL.insertInto(dsl, table("accounts"))
     .set(field("id"), 1)
     .set(field("balance"), 100)
     .onConflict(field("id"))
     .doUpdate()
     .set(field("balance"), 100)
     .execute();
}
```

См. `examples/examples-jooq` и `grid-jooq/README.md`.