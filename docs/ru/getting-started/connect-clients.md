# Подключение клиентов

Приложения, консоль и средства разработки обращаются к запущенному узлу по одному и тому же протоколу. Различается лишь интерфейс, через который с ним работают.

| Кто подключается | Чем | URL |
|------------------|-----|-----|
| Приложение или сервис (реактивный API) | `ConnectionFactory` в `grid-sql-client` | `grid://…` |
| Приложение или сервис (синхронный доступ) | JDBC в `grid-sql-client` (`org.genfork.grid.jdbc`) | `jdbc:grid://…` |
| Инженер в консоли | SQL CLI | `grid://…` |
| Инженер в IDE (DBeaver) | JDBC, тот же драйвер | `jdbc:grid://…` |

Порты по умолчанию: SQL **15432** на primary и **15433** на реплике. Порты **5615** и **5616** отданы транспорту репликации — это другой протокол, клиенты туда не подключаются.

## Приложения

Зависимость нужна только на `grid-sql-client`; движок в процесс приложения не подтягивается.

```xml
<dependency>
  <groupId>org.genfork</groupId>
  <artifactId>grid-sql-client</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

```java
ConnectionFactory factory = ConnectionFactory.fromUrl(
        "grid://app:secret@127.0.0.1:15432,127.0.0.1:15433/public"
                + "?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2");

Mono<Connection> connection = factory.obtain();
```

### Формат URL

```
grid://<пользователь>:<пароль>@<хост>:<порт>[,<хост>:<порт>...]/<схема>?<опции>
```

Несколько хостов в адресной части — это список кандидатов для высокой доступности, а не балансировка записи. Клиент закрепляется на узле, которому разрешено писать, и меняет его только по серверной подсказке (`ServerMeta`, `PROMOTE_NOTIFY`) — см. [повышение роли узла](../configure-and-operate/operations/ha-promote.md).

Часто используемые опции:

| Опция | По умолчанию | Смысл |
|-------|--------------|-------|
| `maxTxContexts` | `256` | Мягкий предел одновременных транзакций на одном сокете (**сторона клиента**) |
| `minConnections` | `1` | TCP-соединения, открываемые при прогреве или первом `obtain` |
| `maxConnections` | `1` | Верхняя граница числа TCP-сокетов в фабрике |
| `connectTimeoutMs` | `5000` | Таймаут подключения |
| `execTimeoutMs` | `0` (выключен) | Таймаут выполнения запроса |
| `retryMode` / `maxRetries` / `retryDelayMs` | `OFF` / `0` / `200` | Повтор connect: `OFF` / `FIXED` / `EXPONENTIAL` |
| `fetchWindow` | `64` | Число строк в одном окне потоковой выдачи |
| `timezone` | `UTC` | Часовой пояс для временных типов |
| `readEndpoints` / `readPreference` / `staleReadPolicy` | — / `PRIMARY` / `FAIL_CLOSED` | Маршрутизация чтения; см. [чтение с реплики](../configure-and-operate/operations/replica-reads.md) |

На **сервере** по умолчанию канал принимает не больше **8** открытых сессий на TCP. Spring Boot **не** прокидывает `grid.sql.max-tx-contexts` в слушатель — если клиент просит больше восьми на одном сокете, откройте дополнительные `Connection` ([SQL-сервер](../configure-and-operate/configuration/sql-server.md), [транзакции](../develop/transactions.md)).

Полный список (включая HA): [Java-клиент](../develop/java-client.md). JDBC и Sync без JDBC: [JDBC-клиент](../develop/jdbc-tooling.md). Смена writer: [повышение роли](../configure-and-operate/operations/ha-promote.md).

### Одно соединение, много транзакций

Один TCP-сокет мультиплексирует независимые транзакции: каждый вызов `connection.begin()` возвращает собственный `TxContext` со своим буфером незафиксированных изменений. Пул «одна транзакция на сокет» здесь не нужен и не помогает — предел это **минимум** из клиентского `maxTxContexts` и серверного потолка канала (**8** сейчас). Подробнее: [транзакции](../develop/transactions.md).

### Чтение с реплики

```
grid://app:secret@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433&readPreference=REPLICA
```

Если в URL указан `readEndpoints`, метод `ConnectionFactory.fromUrl` возвращает маршрутизирующее соединение: `SELECT` и `EXPLAIN` в режиме autocommit уходят на синхронизированную реплику, а записи, транзакции, DDL, `PREPARE` и `FOR UPDATE` остаются на пишущем узле. Собирать две фабрики вручную не требуется.

Чтение собственных, только что записанных строк через реплику не гарантируется. Когда отставание превышает допустимый порог, реплика отвечает отказом, и клиент меняет конечную точку. Подробности: [чтение с реплики](../configure-and-operate/operations/replica-reads.md).

## Реактивный API и JDBC

Оба интерфейса входят в `grid-sql-client` и говорят на одном протоколе. Реактивный (`grid://`, `ConnectionFactory`) — неблокирующий путь на Reactor, JDBC (`jdbc:grid://`) — стабильный синхронный путь для стеков на DataSource/DAO и для IDE. Выбирайте по стеку приложения. Замеры ёмкости и p95 снимают только JMeter по `grid://`: синхронные вызовы искажают задержки.

## Консоль

Для разовых запросов и проверки живости узла есть SQL CLI поверх того же `grid://`: [SQL CLI](../tools/sql-cli.md).

## IDE и отладка

DBeaver и IntelliJ Database используют тот же JDBC-драйвер (`jdbc:grid://127.0.0.1:15432/public`, класс `org.genfork.grid.jdbc.GridDriver`). Нужен JDK 25, в том числе для самого DBeaver: [JDBC-клиент](../develop/jdbc-tooling.md).

Нагрузочные прогоны выполняйте в JMeter поверх `grid://`, а не через JDBC: [нагрузка и SLO](../tools/jmeter-load-slo.md).

## Если подключиться не удаётся

| Симптом | Причина |
|---------|---------|
| `bad frameLen …` | Подключение ушло на порт репликации (**5615** / **5616**) вместо SQL (**15432**) |
| Соединение отклонено | На узле выключен `grid.sql-server.enabled` |
| Readiness в состоянии DOWN или подключение «висит» | ORCHID ещё не синхронизирован — ожидаемо при старте с репликацией; трафик подавать рано |
| В URL несколько хостов, и запись «прыгает» между узлами | Несколько хостов — это список кандидатов, **а не** балансировка записи; закрепление идёт по `writerEligible` / `PROMOTE_NOTIFY` |
| `class file version 69.0` в DBeaver | DBeaver запущен не на JDK 25 |
| `readPreference=REPLICA requires readEndpoints` | Политика чтения задана без списка реплик |
| `REPLICA_READ_STALE` | Отставание реплики выше `max-stale-lag`; клиент сменит конечную точку |

**Связанное:** [Java-клиент](../develop/java-client.md), [транзакции](../develop/transactions.md), [чек-лист перед промышленной эксплуатацией](production-checklist.md), [справочник API](../api-reference/README.md).
