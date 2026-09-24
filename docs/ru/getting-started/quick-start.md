# Быстрый старт

Соберите fat jar, поднимите один узел с записью на диск и выполните несколько SQL-операторов. Пара узлов в режиме HA — в конце страницы.

## Требования

- JDK **25** с `--enable-preview`, как и в самом starter
- Maven, модуль `grid-sql-server-starter`
- Свободный порт **15432** для SQL; для HA дополнительно **15433**, **5615** и **5616**

## Сборка и запуск одного узла с записью на диск

```powershell
mvn -o -pl grid-sql-server-starter -am package -DskipTests
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=capacity
```

Профиль `capacity` слушает SQL на порту **15432** с `fsync: true` и выключенной репликацией. Из IDE тот же профиль запускается классом `org.genfork.grid.sql.SqlServerMain`.

Готовность узла: `http://127.0.0.1:7777/actuator/health/readiness`, если Actuator слушает порт 7777.

## Первый клиент

URL:

```
grid://@127.0.0.1:15432/public
```

Используйте `RemoteConnectionFactory` из `grid-sql-client` или [SQL CLI](../tools/sql-cli.md) для разовых запросов.

```sql
CREATE TABLE IF NOT EXISTS demo (id BIGINT PRIMARY KEY, name VARCHAR);
UPSERT INTO demo (id, name) VALUES (1, 'hello');
SELECT id, name FROM demo WHERE id = 1;
```

SQL разбирается только средствами ANTLR (`SimplifiedSql.g4`). DDL внутри открытой транзакции отклоняется, поэтому схему меняют в режиме autocommit.

## Пара узлов в режиме HA

```powershell
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=primary
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=replica
```

Порты: SQL **15432** / **15433**, репликация **5615** / **5616**. URL с несколькими хостами и закрепление на пишущем узле: [подключение клиентов](connect-clients.md).

## Ограничения

- Порт репликации не является SQL-портом: клиент на **5615** не получит ответа на SQL.
- У каждого процесса свой `dataDir`; общий сетевой том на кластер не поддерживается.
- Цифры, снятые с `fsync: false`, нельзя приводить как заявленный потолок.

## Если узел не поднялся

| Симптом | Что проверить |
|---------|---------------|
| Порт занят, ошибка bind | Другой процесс на **15432** или Actuator на **7777** |
| Readiness в состоянии DOWN | При включённой репликации дождитесь синхронизации ORCHID; без неё смотрите журналы запуска |
| Клиент сообщает `bad frameLen` | Подключение ушло на порт репликации (**5615** / **5616**) вместо SQL |
| Отказ при подключении или аутентификации | В профиле `capacity` пользователей нет — используйте URL без `user:pass` либо создайте пользователя |

## Дальше

1. [Запуск кластера](start-cluster.md) — профили `primary` и `replica`
2. [Java-клиент](../develop/java-client.md) — транзакции, `PREPARE`, потоковая выдача
3. [Чек-лист перед промышленной эксплуатацией](production-checklist.md) — что решить до ввода в эксплуатацию
4. [Compose-стенды](../configure-and-operate/operations/deploy-compose.md) — готовые топологии
