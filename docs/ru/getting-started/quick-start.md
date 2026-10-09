# Быстрый старт

Соберите толстый jar, поднимите один узел с записью на диск, подключитесь без учётных данных (профиль `capacity` — открытый AUTH) и выполните несколько SQL-операторов. Пара узлов в режиме HA — в конце страницы.

## Требования

- JDK **25** с `--enable-preview` (Temurin или аналог)
- Maven **3.9+**
- Локальный clone этого репозитория
- Свободный порт **15432** для SQL; для HA дополнительно **15433**, **5615** и **5616**

На чистой машине: установите JDK 25 и Maven, клонируйте репозиторий, соберите онлайн (не опирайтесь на `mvn -o`, пока локальный репозиторий Maven пуст).

## Сборка и запуск одного узла с записью на диск

```bash
mvn -pl grid-sql-server-starter -am package -DskipTests
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.1.0.jar --spring.profiles.active=capacity
```

Профиль `capacity` слушает SQL на порту **15432** с `fsync: true` и выключенной репликацией. Из IDE тот же профиль запускается классом `org.genfork.grid.sql.SqlServerMain`.

Готовность: `http://127.0.0.1:7778/health/readiness` (в starter Actuator на **7778**, `base-path: /`).

## Первый клиент

В `capacity` пользователей **нет** (открытый AUTH). Первый URL — без учётных данных:

```
grid://127.0.0.1:15432/public
```

JDBC:

```
jdbc:grid://127.0.0.1:15432/public
```

Когда AUTH включат позже — укажите `user:pass@` в authority (например `grid://grid:grid@127.0.0.1:15432/public`).

Используйте `RemoteConnectionFactory` из `grid-sql-client`, JDBC (`org.genfork.grid.jdbc.GridDriver`) или [SQL CLI](../tools/sql-cli.md). В корневом README есть готовые Hello-фрагменты.

```sql
CREATE TABLE IF NOT EXISTS demo (id BIGINT PRIMARY KEY, name VARCHAR);
UPSERT INTO demo (id, name) VALUES (1, 'hello');
SELECT id, name FROM demo WHERE id = 1;
```

SQL разбирается только средствами ANTLR (`SimplifiedSql.g4`). DDL внутри открытой транзакции отклоняется, поэтому схему меняют в режиме autocommit.

## Пара узлов в режиме HA

```bash
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.1.0.jar --spring.profiles.active=primary
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.1.0.jar --spring.profiles.active=replica
```

Порты: SQL **15432** / **15433**, репликация **5615** / **5616**. URL с несколькими хостами и закрепление на пишущем узле: [подключение клиентов](connect-clients.md).

## Ограничения

- Порт репликации не является SQL-портом: клиент на **5615** не получит ответа на SQL.
- У каждого процесса свой `dataDir`; общий сетевой том на кластер не поддерживается.
- Цифры, снятые с `fsync: false`, нельзя приводить как заявленный потолок.

## Если узел не поднялся

| Симптом | Что проверить |
|---------|---------------|
| Порт занят, ошибка bind | Другой процесс на **15432** или Actuator на **7778** (capacity) |
| Readiness в состоянии DOWN | При репликации дождитесь синхронизации ORCHID и `writerEligible`; без неё смотрите журналы запуска |
| Клиент сообщает `bad frameLen` | Подключение ушло на порт репликации (**5615** / **5616**) вместо SQL |
| AUTH отказ с `user:pass` на capacity | В `capacity` пользователей нет — URL **без** учётных данных или создайте пользователя |
| `mvn` не резолвит зависимости | Один раз соберите онлайн (`mvn … package` без `-o`); нужен сеть для холодного репозитория |

## Дальше

1. [Запуск кластера](start-cluster.md) — профили `primary` и `replica`
2. [Java-клиент](../develop/java-client.md) — транзакции, `PREPARE`, потоковая выдача
3. [Spring Boot](../develop/spring-boot.md) — подключение через Boot
4. [Чек-лист перед промышленной эксплуатацией](production-checklist.md) — что решить до ввода в эксплуатацию
5. [Compose-стенды](../configure-and-operate/operations/deploy-compose.md) — готовые топологии
