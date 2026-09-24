# Справочник API

Публичный SPI — в **`grid-sql-client`** (приложения) и **`grid-server-core`** (engine / каталог на серверных узлах). Провод — собственные little-endian кадры Grid (`grid://` / `jdbc:grid://`), не чужой SQL-протокол и не R2DBC.

## Жизненный цикл клиента

`ConnectionFactory` (один на процесс) → `obtain()` → `Connection` (один TCP) → `begin()` / autocommit `Statement` → `TxContext` при необходимости → `dispose()` фабрики при остановке.

| Ошибка | Частая причина |
|--------|----------------|
| AUTH / отказ connect | Неверный user/pass или узел ещё не готов (`readiness` DOWN) |
| `bad frameLen` | Клиент попал на порт репликации (**5615**/**5616**), а не SQL |
| `maxTxContexts=N exhausted` (код кадра 5) | Больше восьми открытых сессий на одном TCP (жёсткий потолок сервера **8**); Boot не поднимает его из YAML — откройте ещё один `Connection` или закройте простаивающие `TxContext` ([SQL-сервер](../configure-and-operate/configuration/sql-server.md)) |
| Stale / `applyLagStale` на чтении с реплики | Отставание apply; `FAIL_CLOSED` — подождать или читать с writer ([чтение с реплики](../configure-and-operate/operations/replica-reads.md)) |
| Отказ записи после повышения роли | Клиент не сделал `rediscoverWriter()` — [повышение роли](../configure-and-operate/operations/ha-promote.md) |
| Отказ по `regionEpoch` / два пишущих | URL записи указывает на Hold/Witness или клиент перебирает следующий адрес в URL без `rediscoverWriter()` |
| TX / DML на read URL | `readEndpoints` / `READ_REPLICA` — только SELECT/EXPLAIN; запись всегда на writer |
| Готовность DOWN при старте | ORCHID ещё не синхронизирован — ожидаемо; не слать трафик |

## Клиентский SPI (`grid-sql-client`)

| Тип | Роль |
|-----|------|
| `ConnectionFactory` | Открытие соединений; `RemoteConnectionFactory`, `RoutingConnectionFactory`; при остановке — `dispose()` |
| `Connection` | Один TCP; мультиплекс сессий |
| `TxContext` | Независимая TX + дескриптор PREPARE (`begin` / `commit` / `rollback`) |
| `Statement` | SQL; `execute` / `executeUpdate`; возвращает клиентский `Result` |
| `Result` / `Row` | Набор строк или счётчик изменений на границе клиентского SPI |
| `SqlResult` | Тип движка / commons на сервере — не то, что приложение получает из `Statement` |
| `ServerMeta` | Метаданные закрепления writer (`writerEligible`, `promoteHint`, `regionEpoch`, …); `lastServerMeta()` на фабрике |
| `PreparedHandle` | Именованный PREPARE на сессии (`prepare` / `bind` / `execute` / `deallocate`) |

### ConnectionFactory

```java
ConnectionFactory f = new RemoteConnectionFactory(
    "127.0.0.1", 15432, "user", "pass", 32);
Mono<Connection> c = f.obtain();
// после смены writer: ((RemoteConnectionFactory) f).rediscoverWriter();
// f.dispose();
```

URL: `grid://user:pass@host:15432[,host:15433]/schema?...`.

| Опция URL | Смысл |
|-----------|--------|
| `maxTxContexts` | Потолок параллельных TX / сессий на одном TCP |
| `readEndpoints` / `readPreference` / `staleReadPolicy` | Маршрутизация SELECT/EXPLAIN; stale = `FAIL_CLOSED` |
| `retryMode` / `maxRetries` / `retryDelayMs` | Повтор connect (`OFF` / `FIXED` / `EXPONENTIAL`); не замена `rediscoverWriter()` |
| `minConnections` / `maxConnections` | Прогрев и потолок TCP в пуле фабрики |
| `fetchWindow` | Строк за один потоковый FETCH |

HA на `RemoteConnectionFactory`: `rediscoverWriter()`, `lastServerMeta()`. Полная таблица URL: [Java-клиент](../develop/java-client.md).

### TxContext / Statement

- Автокоммит: `connection.createStatement(sql).executeUpdate()`
- TX: `connection.begin()` → операторы на `TxContext` → `commit()` / `rollback()`
- Параллельные TX = N `begin()` на одном `Connection` (потолок `maxTxContexts`)

Подробнее: [Java-клиент](../develop/java-client.md), [транзакции](../develop/transactions.md).

## Серверный SPI (`grid-server-core`)

| Тип | Роль |
|-----|------|
| `SqlEngine` | Parse (ANTLR) + execute; `SqlResult` / число затронутых строк |
| `TableCatalog` / `TableSchema` | Схема из DDL; domain = имя таблицы |
| `SqlServer` / `SqlServerRuntime` | Слушатель TCP SQL + runtime |
| `TableStore` | Шардированный store + индексы |

Приложения **не** встраивают удалённый `SqlEngine` — говорят по `grid://` со starter/node. Boot: [Spring Boot](../develop/spring-boot.md).

## JDBC-клиент

JDBC в `grid-sql-client` (`jdbc:grid://`, пакет `org.genfork.grid.jdbc`) — стабильный sync API наравне с reactive: [JDBC-клиент](../develop/jdbc-tooling.md).

## Порты

SQL **15432** / **15433** · репликация **5615** / **5616**.

**Связанное:** [основы SQL](../sql/fundamentals.md), [позиционирование](../getting-started/positioning.md).
