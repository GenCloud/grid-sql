# Java-клиент

Приложения работают с Grid через модуль **`grid-sql-client`**. Библиотека открывает TCP к SQL-порту узла на собственном протоколе (кадры с длиной в начале, little-endian) и даёт **два** равноправных API: reactive (`ConnectionFactory` / Reactor) и синхронный JDBC (`jdbc:grid://`).

Цепочка типов простая:

| Тип | Что это |
|-----|---------|
| `ConnectionFactory` | Точка входа reactive. Держит Netty event loop group и пул TCP-сокетов |
| `Connection` | Транспорт: один сокет, поверх которого мультиплексируются логические сессии |
| `TxContext` | Независимая транзакция на этом транспорте |
| `Statement` | Один SQL-запрос: `bind(...)`, `execute()`, `executeUpdate()`, `fetchOne()` |
| `Result` / `Row` | Результат: либо набор строк, либо счётчик изменённых строк |

JDBC-клиент (`org.genfork.grid.jdbc`) — стабильный sync-вход в том же модуле: [JDBC-клиент](jdbc-tooling.md).

## Зависимость

```xml
<dependency>
  <groupId>org.genfork</groupId>
  <artifactId>grid-sql-client</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

Модуль тянет `grid-commons` (общие типы кадров протокола) и `grid-sql-antlr` (классификация SQL для маршрутизации чтений). Серверные классы (`SqlEngine`, `TableStore`, репликация) в него не входят — приложению они не нужны.

## Подключение

Рекомендуемый путь — URL. `ConnectionFactory.fromUrl` разбирает authority, схему и опции и сам решает, какую реализацию вернуть.

```java
ConnectionFactory factory = ConnectionFactory.fromUrl(
        "grid://app:secret@127.0.0.1:15432,127.0.0.1:15433/public"
                + "?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2");

Mono<Connection> connection = factory.obtain();
```

Программный вариант, когда URL собирать негде:

```java
ConnectionFactory factory = new RemoteConnectionFactory(
        "127.0.0.1", 15432, "app", "secret", /* maxTxContexts */ 32);
```

Фабрику создают **один раз на процесс** (или одну на peer) и переиспользуют; `dispose()` вызывают при остановке приложения.

**Прогрев (опционально):** при `warmup=true` в URL (README / `SqlCli`) или `RemoteConnectionFactory(..., warmup=true)` вызывайте `factory.warmup()`, чтобы открыть `minConnections` TCP в пул простоя (сразу отказ при ошибке connect/AUTH). Первый `obtain()` также заполняет минимальный пул при необходимости — обязательного ожидания warmup нет. Демо: [`examples/examples-warmup`](../../../examples/examples-warmup).

### Схема URL

```
grid://<user>:<password>@<host>:<port>[,<host>:<port>...]/<schema>?<опции>
```

| Опция | По умолчанию | Смысл |
|-------|--------------|-------|
| `minConnections` | `1` | TCP, открываемые на прогрев / первом `obtain` (clamp до `maxConnections`) |
| `maxConnections` | `1` | Верхняя граница TCP-сокетов в пуле фабрики |
| `maxTxContexts` | `256` | потолок одновременных логических сессий на одном сокете (`0` — без предела) |
| `connectTimeoutMs` | `5000` | Таймаут установления соединения |
| `execTimeoutMs` | `0` (выкл.) | Таймаут выполнения запроса |
| `readTimeoutMs` / `writeTimeoutMs` | `0` (выкл.) | Таймауты канала |
| `retryMode` | `OFF` | `OFF` / `FIXED` / `EXPONENTIAL` — повтор connect при отказе адреса (`FIXED` — постоянная `retryDelayMs`; `EXPONENTIAL` удваивает задержку от `retryDelayMs` на каждой попытке) |
| `maxRetries` / `retryDelayMs` | `0` / `200` | Число повторов и базовая задержка |
| `timezone` | `UTC` | Зона для временных типов в bind и в результатах |
| `fetchWindow` | `64` | Строк за один FETCH при потоковой выдаче |
| `readEndpoints` | — | Список реплик для чтения; включает маршрутизацию |
| `readPreference` | `PRIMARY` | `PRIMARY` / `REPLICA`; `REPLICA` требует `readEndpoints` |
| `staleReadPolicy` | `FAIL_CLOSED` | При отставании реплики выше порога сервера — отказ чтения (в v1 единственное значение); см. [чтение с реплики](../configure-and-operate/operations/replica-reads.md) |
| `maxReadConnections` | `1` | Сокеты в пуле чтения |
| `warmup` | `false` | Если `true`, явный `warmup()` прогревает `minConnections` (не блокирует `obtain`) |

Несколько хостов в authority — это **не** балансировка записи, а список кандидатов: клиент закрепляется на узле, который может писать (`writerEligible`) и меняет его только по `ServerMeta` / `PROMOTE_NOTIFY`. Подробнее — [повышение роли узла](../configure-and-operate/operations/ha-promote.md).

### Смена writer в приложении

После failover **не** крутите следующий host в URL вручную.

| Сигнал | Что делать приложению |
|--------|----------------------|
| `PROMOTE_NOTIFY` / обновлённый `ServerMeta` на живом канале | Следовать promote-подсказке; фабрика может вызвать `rediscoverWriter()` сама |
| Запись всё ещё падает / липнет к мёртвому host | Вызвать `rediscoverWriter()` на `RemoteConnectionFactory` (новый `Connection`); смотреть `lastServerMeta()` / `connection.serverMeta()` — `writerEligible`, `promoteHint`, `regionEpoch` |

Повторы connect (`retryMode`) не заменяют rediscover после смены роли. Регламент: [повышение роли](../configure-and-operate/operations/ha-promote.md).

## Автокоммит

Самый частый режим: каждый запрос — своя короткая транзакция на сервере.

```java
Mono<Long> updated = connection
        .createStatement("UPSERT INTO accounts (id, balance) VALUES (?, ?)")
        .bind(0, 1L)
        .bind(1, 100L)
        .executeUpdate();

Flux<String> names = connection
        .createStatement("SELECT name FROM accounts WHERE balance > ?")
        .bind(0, 0L)
        .execute()
        .flatMap(result -> result.map((row, meta) -> row.get("name", String.class)));
```

Несколько запросов одним RTT — `connection.executeBatch(List.of(...))`. В autocommit каждый запрос коммитится сам по себе; общей транзакции у них нет.

## Транзакции

```java
Mono<Void> transfer = Mono.usingWhen(
        connection.begin(),
        tx -> tx.createStatement("UPDATE accounts SET balance = balance - 10 WHERE id = 1")
                .executeUpdate()
                .then(tx.createStatement("UPDATE accounts SET balance = balance + 10 WHERE id = 2")
                        .executeUpdate())
                .then(tx.commit()),
        TxContext::rollback);
```

`Mono.usingWhen` здесь не украшение: он гарантирует, что при ошибке или отмене подписки транзакция будет закрыта, и делает это без блокировки. Детали модели — [транзакции](transactions.md).

### PREPARE

```java
Mono<Long> once = connection.prepare("upd_bal",
                "UPDATE accounts SET balance = ? WHERE id = ?")
        .flatMapMany(h -> h.bind(0, 50L).bind(1, 1L).execute()
                .concatWith(Flux.defer(h::deallocate)))
        .then(Mono.just(1L));
```

Имя подготовленного оператора хранится в сессии (`TxContext` / соединение). Другая TCP-сессия его не видит. После работы вызывайте `deallocate`; закрытие соединения снимает все PREPARE этой сессии.

### Помощники сессии

| Метод | Роль |
|-------|------|
| `connection.pin(table, key[, ttlMs[, qos]])` / `unpin` | Мягкое закрепление в overlay — [Overlay PIN](../configure-and-operate/configuration/overlay-pin.md) |
| `connection.setSchema(schema)` | `SET SCHEMA` для сессии |
| `connection.setTimezone(zoneId)` | Часовой пояс сессии (удалённое соединение) |

### Про `.block()`

`Mono` и `Flux` в этом API ленивы: пока на них не подписались, на сервер ничего не ушло.

`.block()` допустим **только на синхронной границе приложения** — `main`, CLI, JUnit/JMH стенд, отдельный platform-поток шедулера. Внутри библиотечного кода, реактивных пайплайнов, Netty event loop и на потоке-носителе virtual thread под долгую работу блокировка запрещена: она останавливает event loop или закрепляет носитель VT, и деградирует не один запрос, а весь процесс.

```java
// граница приложения — так можно
public static void main(String[] args) {
    final Long rows = connection.createStatement("SELECT 1").executeUpdate().block();
}

// внутри сервиса — так нельзя
public void handle() {
    connection.createStatement("...").executeUpdate().block(); // остановит EL / закрепит носитель VT
}
```

Вместо блокировки внутри сервиса возвращайте `Mono` / `Flux` наружу и компонуйте через `flatMap`, `then`, `usingWhen`, `doFinally`.

## ReactiveSqlOps

Пакет `org.genfork.grid.sql.client.ops` — тонкие хелперы над тем же SPI: bind-all, `executeUpdate`, `fetchOne` / `fetchList` / `fetchExists`, упорядоченные шаги (`BoundSql`) и `RowMapper`. Жизненный цикл TX (`begin` / `commit` / `close`) они **не** владеют — это остаётся у вызывающего кода. На явной sync-границе (`main`, CLI, тесты) используйте `SyncConnection` / `SyncTxContext` напрямую (или SyncAwait), без отдельного ops-фасада.

| Хелпер | Когда |
|--------|--------|
| `ReactiveSqlOps` | Обычный путь приложения: `Mono` / `Flux`, без `.block()` и без SyncAwait |

```java
// reactive — компоновка в пайплайне
Mono<Optional<String>> name = ReactiveSqlOps.fetchOptional(
        tx, "SELECT name FROM accounts WHERE id = ?", row -> row.get(0, String.class), id);
```

## Параллельные транзакции

`connection.begin()` можно вызвать несколько раз: каждая `TxContext` — независимая транзакция со своим dirty-буфером и своим дескриптором PREPARE, все они идут по **одному** TCP-сокету.

```java
Mono<Void> parallel = Mono.zip(
        runTx(connection, "UPDATE t SET v = v + 1 WHERE id = 1"),
        runTx(connection, "UPDATE t SET v = v + 1 WHERE id = 2")
).then();
```

Это принципиально другая модель, чем пул JDBC-соединений: не нужно `maxTxContexts` сокетов, нужен один сокет и `maxTxContexts` логических сессий. Он ограничивает `SESSION_OPEN`, а не число потоков приложения.

## Чтение с реплик

Если в URL указать `readEndpoints`, `ConnectionFactory.fromUrl` вернёт маршрутизирующее соединение: autocommit `SELECT` / `EXPLAIN` уйдут на наименее загруженную синхронную реплику, а записи, транзакции, DDL, `PREPARE` и `FOR UPDATE` — на writer. Классификацию делает общий ANTLR-классификатор, одинаковый на клиенте и на сервере.

```
grid://app:secret@primary:15432/public?readEndpoints=replica-1:15433,replica-2:15434&readPreference=REPLICA&maxReadConnections=2
```

Чтение своих записей через реплику без лишнего обхода через writer не гарантируется. При превышении допустимого отставания реплика отвечает `REPLICA_READ_STALE`, и клиент меняет адрес реплики. Полный разбор политик, отказов и рисков — [чтение с реплики](../configure-and-operate/operations/replica-reads.md).

## Потоковая выдача больших выборок

Результат приходит окнами: клиент просит `fetchWindow` строк, сервер отдаёт ровно столько и ждёт следующего FETCH. Окно задаётся на URL или на конкретном запросе — `statement.fetchWindow(256)`. См. [потоковую выдачу](wire-streaming.md).

## JDBC-клиент и Sync без JDBC

Пакет `org.genfork.grid.jdbc` и URL `jdbc:grid://` — стабильный синхронный API на том же протоколе, что и reactive. Sync-фасад: `JdbcSync` → `SyncAwait` на `SyncExecExchange` / `SyncBatchExchange`. Reactive SPI — `ReactiveExecExchange` / `ReactiveBatchExchange`. Fat jar для IDE: `mvn -pl grid-sql-client -am package -DskipTests` → `grid-sql-client/target/grid-sql-client-*-dbeaver.jar`.

Для **синхронного приложения без JDBC** используйте `SyncConnectionFactory.fromUrl(gridUrl)` → `open()` → `SyncConnection` (те же опции URL; park/close как у фабрики). Подробности: [JDBC-клиент](jdbc-tooling.md) (§ Sync без JDBC). Замеры ёмкости — только JMeter на `grid://`, не через JDBC.

## Примеры для запуска

Примеры `grid-sql-client` лежат в [`examples/`](../../../examples/). Опциональный `factory.warmup()` при `?warmup=true` прогревает `minConnections` (сразу отказ при ошибке connect/AUTH) — см. `examples-warmup`. Канал берётся через `factory.obtain()`. Остальные модули: connect, session, TX, batch, PREPARE, savepoints, потоковая выдача, parallel TX, чтение с реплики, HA URL, DML, indexes, JOIN/agg, EXPLAIN, `FOR UPDATE`, PIN. `GRID_URL` — на живой SQL-порт (`capacity` или `examples/compose/1dc-n2`). См. [`examples/README.ru.md`](../../../examples/README.ru.md).

**Связанное:** [транзакции](transactions.md), [Spring Boot](spring-boot.md), [потоковая выдача](wire-streaming.md), [подключение клиентов](../getting-started/connect-clients.md), [справочник API](../api-reference/README.md).
