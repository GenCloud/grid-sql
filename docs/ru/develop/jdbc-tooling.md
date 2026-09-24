# JDBC-клиент

Стабильный **синхронный** клиент Grid в модуле **`grid-sql-client`**, пакет `org.genfork.grid.jdbc` (FQCN драйвера: `org.genfork.grid.jdbc.GridDriver`). Отдельного модуля `grid-jdbc` нет.

JDBC стоит **наравне** с reactive API (`ConnectionFactory` / Reactor): один протокол (кадры little-endian на SQL-порту), два входа в приложение. Внутри драйвера `GridDriver` и `GridDataSource` делят один `SyncConnectionFactory.shared` на URL target; obtain/park только через Sync* (`obtainStage` + `SyncAwait` / `SyncExecutors`, без `Mono.toFuture`). `JdbcSync` только маппит в `SQLException`. Лимиты TCP — в Sync*/`RemoteConnectionFactory`, без JDBC pool registry. `Connection.close` = park; `DataSource.close` = release retain. Разбор входящих кадров общий для reactive и sync.

Интеграционные тесты драйвера — в `grid-server-core` (`GridJdbcIT`).

## Когда JDBC / когда reactive

| Сценарий | Использовать |
|----------|--------------|
| Сервис на sync JDBC / DataSource / классический DAO | JDBC (`jdbc:grid://`, `org.genfork.grid.jdbc`) |
| Sync-приложение **без** типов JDBC | `SyncConnectionFactory` + `SyncAwait` (тот же модуль) |
| Сервис на Reactor / много параллельных запросов без блокировок | Reactive: `ConnectionFactory` + `grid://` |
| DBeaver / IntelliJ Database, разовый SQL | JDBC (тот же драйвер) |
| Замер ёмкости / p95 / JMeter | Только `grid://` (reactive) — sync искажает задержки |

## Sync без JDBC

Если стек синхронный, но `java.sql.*` не нужен, берите Sync-фасад поверх того же `grid://`:

```java
try (SyncConnectionFactory factory = SyncConnectionFactory.fromUrl(
        "grid://app:secret@127.0.0.1:15432/public")) {
    SyncConnection c = factory.open();
    try {
        long n = c.executeUpdate("UPSERT INTO t (id) VALUES (1)");
    } finally {
        c.close(); // park канала в пул простоя
    }
}
```

`fromUrl` разделяет семантику URL с `ConnectionFactory.fromUrl` (включая `readEndpoints`). Ожидание obtain идёт через `RemoteConnectionFactory.obtainStage()` и `SyncAwait` — никогда `Mono.toFuture`. При остановке закройте фабрику (`AutoCloseable`). Reactive-путь: [Java-клиент](java-client.md).

## Пример в сервисе

```java
Class.forName("org.genfork.grid.jdbc.GridDriver");
try (Connection c = DriverManager.getConnection(
        "jdbc:grid://user:pass@127.0.0.1:15432/public")) {
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT id FROM t LIMIT 10")) {
        while (rs.next()) {
            // …
        }
    }
}
```

Зависимость — тот же артефакт `grid-sql-client` (или fat jar `*-dbeaver.jar` для IDE). Reactive удобнее, когда нужен неблокирующий параллельный обмен на одном event loop; JDBC — когда стек приложения уже sync (Spring JDBC, MyBatis, ручной DAO).

## Runnable-примеры

В [`examples/`](../../../examples/): `examples-jdbc-connect`, `examples-jdbc-dml`, `examples-jdbc-tx`, `examples-jdbc-savepoints`, `examples-jdbc-batch`, `examples-jdbc-session`. Тот же SQL-порт, что у reactive-демо; URL через `GRID_URL` → `jdbc:grid://…`. См. [`examples/README.ru.md`](../../../examples/README.ru.md).

## Требование к JRE

Драйвер и его fat jar собраны под **Java 25** (версия класс-файла 69). Встроенная JRE DBeaver обычно 17 или 21, и тогда при подключении вы увидите:

```
GridDriver has been compiled by a more recent version of the Java Runtime
(class file version 69.0), this version only recognizes class file versions up to 65.0
```

Лечится запуском самого DBeaver на JDK 25. В `dbeaver.ini` **до** строки `-vmargs`:

```
-vm
C:/path/to/jdk-25/bin
```

Путь должен указывать на каталог `bin` с `javaw.exe`. После правки — перезапуск. В IntelliJ достаточно выставить Project JDK 25. Сборки этого стека под Java 21 нет: `grid-sql-client` компилируется на Java 25 с `--enable-preview`.

## Сборка драйвера

```powershell
mvn -pl grid-sql-client -am package -DskipTests
```

Для Custom Driver берите `grid-sql-client/target/grid-sql-client-*-dbeaver.jar` — в нём уже лежат транзитивные зависимости (Maven classifier `dbeaver`).

## Подключение в DBeaver

1. **Database → Driver Manager → New**, добавьте jar.
2. Class name: `org.genfork.grid.jdbc.GridDriver`
3. URL template / URL: `jdbc:grid://u:p@127.0.0.1:15432/public`

| Поле | Значение |
|------|----------|
| Host | `127.0.0.1` |
| Port | **15432** (реплика — **15433**) |
| Database / Schema | `public` |
| User / Password | Пустой каталог — можно без пароля; после первого `CREATE USER` — те же credentials, что в AUTH (см. [безопасность](../configure-and-operate/operations/security.md)) |

Формат URL тот же, что у `grid://`, только с префиксом `jdbc:`: `jdbc:grid://user:pass@h1:15432,h2:15433/public`. Параметр `?hosts=` не поддерживается — несколько хостов пишутся через запятую в authority.

Драйвер снимает префикс `jdbc:` и разбирает URL тем же парсером, что reactive-клиент. Точка входа — **`SyncConnectionFactory.fromUrl` / `shared`** (те же product URL semantics, что `ConnectionFactory.fromUrl`): при `readEndpoints` + `readPreference=REPLICA` autocommit SELECT/EXPLAIN уходят в read pool. Наследуются опции `grid://`: `maxTxContexts`, `readEndpoints`, `readPreference`, `fetchWindow`, кольцо хостов для HA. Подробности URL: [подключение клиентов](../getting-started/connect-clients.md).

Пример replica URL:

```
jdbc:grid://u:p@127.0.0.1:15432/public?readPreference=REPLICA&readEndpoints=127.0.0.1:15433,127.0.0.1:15434
```

## Что работает

- Дерево схемы: каталог, таблицы, колонки, индексы.
- SQL Editor: разовые `SELECT` и DML в пределах упрощённого диалекта.
- **Многооператорные скрипты** (через `;`): ANTLR `script` → `BATCH_EXEC` (DBeaver script без Bad SQL).
- Транзакции, в том числе точки сохранения. Параллельные TX = N JDBC `Connection` с одной фабрики (multiplex), не один TX на сокет.
- Прокручиваемый `ResultSet` и Data Editor — для одиночной таблицы с первичным ключом (materialize для IDE).
- `Statement.cancel()` отменяет in-flight SyncAwait и шлёт wire `CANCEL`.
- `Statement.setQueryTimeout` → бюджет SyncAwait; timezone через `setClientInfo("timezone", …)` / unwrap `SyncConnection`.
- Unwrap: `SyncConnection`, `ServerMeta`, `SyncConnectionFactory`; `pin` / `unpin` на `GridConnection`.
- Метаданные колонок приходят из `RowMetadata` / `ROW_DESC`; JDBC-подсказки каталога используются только как запасной путь.
- `isValid()` проверяет жизненный цикл канала и не гоняет `SELECT 1`.

## Частая ошибка: `bad frameLen …`

Значит, на сокете нет SQL-фреймов Grid. Почти всегда это неверный порт: **5615 — транспорт репликации**, **15432 — SQL**. Второй вариант — SQL TCP просто не включён: проверьте `grid.sql-server.enabled: true`.

| Симптом | Причина |
|---------|---------|
| Class file version 69 | DBeaver/IDE на JDK < 25 — см. выше |
| AUTH / пустой каталог | Сервер ждёт пользователей, а URL без credentials (или наоборот) |

## Чего не делать

- Не измерять через JDBC ёмкость и SLO. Нагрузка снимается JMeter поверх `grid://`: [нагрузка и SLO](../tools/jmeter-load-slo.md).
- `jdbc:grid://` — тот же продуктовый протокол Grid с префиксом `jdbc:`, не второй стек провода.
- Не наводить клиент на порт репликации (**5615** / **5616**).

Нужна консоль вместо IDE — есть [SQL CLI](../tools/sql-cli.md). Reactive-путь: [Java-клиент](java-client.md).

**Связанное:** [Java-клиент](java-client.md), [подключение клиентов](../getting-started/connect-clients.md), [безопасность](../configure-and-operate/operations/security.md), [SQL-сервер](../configure-and-operate/configuration/sql-server.md), [DBeaver README](../../tools/dbeaver/README.md).
