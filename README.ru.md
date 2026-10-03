# Grid SQL

![Grid](docs/assets/brand/grid-wordmark.svg)

[![CI](https://github.com/GenCloud/grid-sql/actions/workflows/ci.yml/badge.svg)](https://github.com/GenCloud/grid-sql/actions/workflows/ci.yml)
[![Jepsen QG](https://github.com/GenCloud/grid-sql/actions/workflows/jepsen-qg.yml/badge.svg)](https://github.com/GenCloud/grid-sql/actions/workflows/jepsen-qg.yml)

**Нужна скорость SQL в Java без отдельного кэша, который разъезжается с журналом?** Grid — SQL-first СУБД в памяти с долговременным хранением (OpLog + sealed GridMap) и репликацией ORCHID. Один протокол для приложений: `grid://` (реактивный) или `jdbc:grid://` (JDBC).

**5 минут:** [быстрый старт (RU)](docs/ru/getting-started/quick-start.md) · [quick start (EN)](docs/en/getting-started/quick-start.md) · [README (EN)](README.md)

Лицензия Apache License, Version 2.0 — см. `LICENSE` и `NOTICE`.

## Быстрый старт

Нужны Java **25** (`--enable-preview`) и Maven **3.9+**.

```bash
mvn -pl grid-sql-server-starter -am package -DskipTests
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=capacity
```

Профиль `capacity` слушает SQL на порту **15432**, запись на диск включена, репликация выключена (**открытый AUTH** — пользователей ещё нет).

### Hello (JDBC)

```java
Class.forName("org.genfork.grid.jdbc.GridDriver");
try (java.sql.Connection c = java.sql.DriverManager.getConnection(
        "jdbc:grid://127.0.0.1:15432/public")) {
    c.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS demo (id BIGINT PRIMARY KEY, name VARCHAR)");
    c.createStatement().execute("UPSERT INTO demo (id, name) VALUES (1, 'hello')");
}
```

### Hello (реактивный)

```java
org.genfork.grid.sql.client.RemoteConnectionFactory factory =
        new org.genfork.grid.sql.client.RemoteConnectionFactory(
                "127.0.0.1", 15432, "", "", 8);
factory.obtain()
        .flatMap(conn -> conn.createStatement(
                "UPSERT INTO demo (id, name) VALUES (1, 'hello')").executeUpdate())
        .block(); // только демо — не в library API
```

URL: `grid://127.0.0.1:15432/public` · `jdbc:grid://127.0.0.1:15432/public`.  
Если AUTH включён — укажите `user:pass@` в authority (например `grid://grid:grid@…`).

## Доказательства (лабораторный хост)

| Трек | Живая цифра |
|------|-------------|
| HA WRITE_ONLY | ≈**4676**/с (порог регресса ≈**4442**/с) |
| Согласованность | матрица Jepsen — [`COVERAGE.md`](benchmarks/jepsen/COVERAGE.md) |

Подробности: [ёмкость](docs/ru/performance/capacity-slo.md), [результаты](docs/ru/performance/results.md), [методика](docs/ru/performance/methodology.md).

## Дальше

| Тема | Документ |
|------|----------|
| Spring Boot | [spring-boot](docs/ru/develop/spring-boot.md) |
| Репликация ORCHID | [orchid-consensus](docs/ru/understand/orchid-consensus.md) |
| Смена пишущего | [ha-promote](docs/ru/configure-and-operate/operations/ha-promote.md) |
| Чек-лист go-live | [production-checklist](docs/ru/getting-started/production-checklist.md) |
| Хаб документации | [docs/ru/README-ru.md](docs/ru/README-ru.md) · [EN hub](docs/README.md) |

Полный английский README (CI, модули, границы): [README.md](README.md).

## Участие

См. [CONTRIBUTING.md](CONTRIBUTING.md) и [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
