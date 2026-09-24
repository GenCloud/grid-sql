# SQL CLI

`org.genfork.grid.sql.cli.SqlCli` в **`grid-sql-client`** — интерактивная оболочка к работающему узлу. Стек тот же, что у приложения: `grid://` и `RemoteConnectionFactory`, без JDBC. DDL, транзакции и PREPARE ведут себя так же, как в клиентском коде.

Нужна для разовых DDL/DML, быстрой проверки схемы и ручной отладки транзакций и PREPARE. TPS и p95 измеряют через [JMeter](jmeter-load-slo.md).

## Когда подходит

| Задача | CLI | Чем заменить |
|--------|-----|--------------|
| Разовый `CREATE TABLE` или проверка `SELECT` | Да | — |
| BEGIN / COMMIT / SAVEPOINT вручную | Да | — |
| PREPARE / EXECUTE / DEALLOCATE | Да (`\prepare` …) | — |
| Измерение TPS и p95 | Нет | [JMeter](jmeter-load-slo.md) |
| Браузер схемы в IDE | Нет | [JDBC-клиент](../develop/jdbc-tooling.md) |

`.block()` в потоке CLI допустим: `main` — граница приложения, не API библиотеки.

## Область применения

CLI подключается по `grid://` (свой протокол Grid), без JDBC. Meta-команд вроде `\d` и истории ввода нет. URL и AUTH те же, что у приложения — удобно проверить DDL и транзакции, прежде чем писать клиентский код.

## Запуск

```powershell
mvn -o -pl grid-sql-client -am package -DskipTests
java --enable-preview -cp grid-sql-client/target/classes;... org.genfork.grid.sql.cli.SqlCli -h 127.0.0.1 -p 15432
```

Параметры подключения задают одним из двух способов:

| Форма | Пример |
|-------|--------|
| URL | `grid://user:pass@127.0.0.1:15432/public` |
| Флаги | `-h` / `--host`, `-p` / `--port`, `-u` / `--user`, `-P` / `--password` |

По умолчанию хост `127.0.0.1` и порт SQL **15432**; реплика локальной пары отдаёт чтения на **15433**, если профиль replica поднят. Порты **5615** / **5616** занимает репликация, а не SQL: там CLI получит битый кадр.

## Типичные ошибки

Ошибки операторов печатаются в **stderr**, интерактивная сессия продолжается (`quit` / `exit` / EOF завершают процесс). Сбой connect прерывает `main` исключением (ненулевой статус JVM). Отдельного кода выхода процесса на каждую ошибку SQL нет.

| Симптом | Причина |
|---------|---------|
| `bad frameLen` | CLI направлен на порт репликации (**5615** / **5616**) |
| Отказ AUTH | Неверные логин или пароль либо на узле есть пользователи, а в URL их нет |
| Schema или table not found | Не та схема в URL (обычно нужен `/public`) или DDL ещё не выполнен |
| Таймаут подключения | Узел не слушает SQL, не тот хост или порт, либо readiness всё ещё DOWN |
| `maxTxContexts=8 exhausted` | Больше восьми открытых сессий на одном TCP — другое соединение или закрыть idle TX |

## Команды сессии

Одна строка — один оператор (Simplified SQL). Пустая строка, `quit` или `exit` завершают работу.

| Ввод | Поведение |
|------|-----------|
| `BEGIN` / `COMMIT` / `ROLLBACK` | Один текущий `TxContext` на соединение |
| `SAVEPOINT name` / `ROLLBACK TO name` | Точка сохранения внутри открытой транзакции |
| Обычный SQL | Autocommit, если транзакция не открыта, иначе внутри неё |
| `\prepare name AS …` | Именованный `PreparedHandle` |
| `\execute name …` | Выполнить подготовленный запрос |
| `\deallocate name` | Снять подготовленный запрос |

## Ограничения

- Одна строка за раз: нет многострочного редактора и интерактивной истории команд.
- Нет форматирования EXPLAIN сверх текста, который вернул сервер.
- Не заменяет JDBC для приложений и DBeaver — это пакет `org.genfork.grid.jdbc` в `grid-sql-client`.
- Не поднимает узел: SQL TCP должен уже слушать (`grid.sql-server.enabled`).

## Рядом

- Поднять узел: [быстрый старт](../getting-started/quick-start.md), [запуск кластера](../getting-started/start-cluster.md)
- URL и закрепление пишущего узла: [подключение клиентов](../getting-started/connect-clients.md)
- Настройка порта: [SQL-сервер](../configure-and-operate/configuration/sql-server.md)
- API приложения: [Java-клиент](../develop/java-client.md), [транзакции](../develop/transactions.md)

Английская версия: [sql-cli.md](../../en/tools/sql-cli.md).
