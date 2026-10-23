# Справочник API

Публичный SPI — в **`grid-sql-client`** (приложения) и **`grid-server-core`** (engine / каталог на серверных узлах). Провод — собственные little-endian кадры Grid (`grid://` / `jdbc:grid://`), не чужой SQL-протокол и не R2DBC.

## Жизненный цикл клиента

`ConnectionFactory` (один на процесс) → `obtain()` → `Connection` (один TCP) → `begin()` / autocommit `Statement` → `TxContext` при необходимости → `dispose()` фабрики при остановке.

| Ошибка | Частая причина |
|--------|----------------|
| AUTH / отказ connect | Неверный user/pass или узел ещё не готов (`readiness` DOWN) |
| `bad frameLen` | Клиент попал на порт репликации (**5615**/**5616**), а не SQL |
| `maxTxContexts=N exhausted` (код кадра 5) | Больше открытых сессий на одном TCP, чем потолок сервера (по умолчанию **8**, `grid.sql-server.max-tx-contexts`) — поднимите YAML и/или откройте ещё один `Connection` / закройте простаивающие `TxContext` ([SQL-сервер](../configure-and-operate/configuration/sql-server.md)) |
| Stale / `applyLagStale` на чтении с реплики | Отставание apply; при превышении порога — **отказ** (подождать или читать с пишущего) ([чтение с реплики](../configure-and-operate/operations/replica-reads.md)) |
| Отказ записи после повышения роли | Клиент не сделал `rediscoverWriter()` — [повышение роли](../configure-and-operate/operations/ha-promote.md) |
| Отказ по `regionEpoch` / два пишущих | URL записи указывает на Hold/Witness или клиент перебирает следующий адрес в URL без `rediscoverWriter()` |
| TX / DML на read URL | `readEndpoints` / `READ_REPLICA` — только SELECT/EXPLAIN; запись всегда на пишущий узел |
| Готовность DOWN при старте | ORCHID ещё не синхронизирован — ожидаемо; не слать трафик |
| EXEC-ошибка с ` \| sql=… binds=[…]` | Сервер обогащает **текст** через `SqlExecErrorContextUtil` (SQL ≤ **512** символов, binds редактируются); remote → `IllegalStateException` / JDBC `SQLException` с этим текстом |
| `domain=… index=… value=[…] not unique for strict index.` | Форма сообщения коллизии STRICT / UNIQUE; типы `NonUniqueValueException` / `SqlExecException` — только **server in-process** |

## Клиентский SPI (`grid-sql-client`)

| Тип | Роль |
|-----|------|
| `ConnectionFactory` | Открытие соединений; `RemoteConnectionFactory`, `RoutingConnectionFactory`; при остановке — `dispose()` |
| `Connection` | Один TCP; мультиплекс сессий |
| `TxContext` | Независимая TX + дескриптор PREPARE (`begin` / `commit` / `rollback`) |
| `Statement` | SQL; `execute` / `executeUpdate`; возвращает клиентский `Result` |
| `Result` / `Row` | Набор строк или счётчик изменений на границе клиентского SPI |
| `SqlResult` | Тип движка / commons на сервере — не то, что приложение получает из `Statement` |
| `ServerMeta` | Метаданные HA для закрепления на пишущем узле (`writerEligible`, `promoteHint`, `regionEpoch`, …); `lastServerMeta()` на фабрике |
| `PreparedHandle` | Именованный PREPARE на сессии (`prepare` / `bind` / `execute` / `deallocate`) |

### ConnectionFactory

```java
ConnectionFactory f = new RemoteConnectionFactory(
    "127.0.0.1", 15432, "user", "pass", 32);
Mono<Connection> c = f.obtain();
// после смены пишущего: ((RemoteConnectionFactory) f).rediscoverWriter();
// f.dispose();
```

URL: `grid://user:pass@host:15432[,host:15433]/schema?...`.

| Опция URL | Смысл |
|-----------|--------|
| `maxTxContexts` | Мягкий потолок клиента на параллельные TX / сессии на одном TCP (по умолчанию **256**); на сервере default **8** (`grid.sql-server.max-tx-contexts`) |
| `readEndpoints` / `readPreference` / `staleReadPolicy` | Маршрутизация SELECT/EXPLAIN; при отставании — отказ (не отдавать устаревшее) |
| `retryMode` / `maxRetries` / `retryDelayMs` | Повтор connect (`OFF` / `FIXED` / `EXPONENTIAL`); не замена `rediscoverWriter()` |
| `minConnections` / `maxConnections` | Прогрев и потолок TCP в пуле фабрики |
| `fetchWindow` | Строк за один потоковый FETCH |

HA на `RemoteConnectionFactory`: `rediscoverWriter()`, `lastServerMeta()`. Полная таблица URL: [Java-клиент](../develop/java-client.md).

### TxContext / Statement

- Автокоммит: `connection.createStatement(sql).executeUpdate()`
- TX: `connection.begin()` → операторы на `TxContext` → `commit()` / `rollback()`
- Параллельные TX = N `begin()` на одном `Connection` (клиентский `maxTxContexts`; серверный default **8**, поднимается YAML)

Подробнее: [Java-клиент](../develop/java-client.md), [транзакции](../develop/transactions.md).

## Серверный SPI (`grid-server-core`)

| Тип | Роль |
|-----|------|
| `SqlEngine` | Parse (ANTLR) + execute; `SqlResult` / число затронутых строк |
| `TableCatalog` / `TableSchema` | Схема из DDL; домен репликации/OpLog/sealed = ключ каталога (`public` может быть голым именем таблицы; иначе `schema.table`) |
| `SqlServer` / `SqlServerRuntime` | Слушатель TCP SQL + runtime |
| `TableStore` | Шардированный store + индексы |

Приложения **не** встраивают удалённый `SqlEngine` — говорят по `grid://` со starter/node. Boot: [Spring Boot](../develop/spring-boot.md).

Потолок открытых сессий на TCP-канале слушателя по умолчанию **8** (`grid.sql-server.max-tx-contexts`). Файл каталога привилегий: `{grid.sql.data-dir}/catalog/privileges.meta` (локальный файл узла; blob репликация **не** развозит — privilege DDL идёт в OpLog `_catalog`) — [безопасность](../configure-and-operate/operations/security.md), [SQL-сервер](../configure-and-operate/configuration/sql-server.md).

## JDBC-клиент

JDBC в `grid-sql-client` (`jdbc:grid://`, пакет `org.genfork.grid.jdbc`) — стабильный синхронный API наравне с реактивным: [JDBC-клиент](../develop/jdbc-tooling.md).

## Порты

SQL **15432** / **15433** · репликация **5615** / **5616**.

Дальше: [основы SQL](../sql/fundamentals.md), [позиционирование](../getting-started/positioning.md).
