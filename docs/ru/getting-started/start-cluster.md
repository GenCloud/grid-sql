# Запуск кластера

Минимальная конфигурация с высокой доступностью — два узла, `primary` и `replica`. Оба пишут на диск, оба указывают друг друга в списке соседей и каждый работает со своим каталогом данных.

## Локальная пара 1+1

```mermaid
flowchart LR
  subgraph clients [Клиенты]
    App[Приложение_grid]
  end
  subgraph pair [Один_ЦОД_1plus1]
    P["primary SQL :15432 repl :5615"]
    R["replica SQL :15433 repl :5616"]
  end
  App -->|запись_grid://| P
  App -.->|чтение_по_желанию| R
  P <-->|Netty_репликация| R
```

Соберите рабочий jar один раз:

```powershell
mvn -o -pl grid-sql-server-starter -am package -DskipTests
```

Запустите два процесса с готовыми профилями:

```powershell
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=primary
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=replica
```

| Процесс | Профиль | SQL | Репликация | Actuator | `dataDir` |
|---------|---------|----:|-----------:|---------:|-----------|
| primary | `primary` | **15432** | **5615** | **7777** | `./data-primary/…` |
| replica | `replica` | **15433** | **5616** | **7778** | `./data-replica/…` |

Оба профиля поставляются с `fsync: true`, разными портами Actuator и уже указывают друг друга в `peers`, поэтому для локального стенда править конфигурацию вручную не нужно.

Три вещи, на которых спотыкаются чаще всего:

- **У каждого узла свой `dataDir`.** Общий каталог или сетевая шара на весь кластер не поддерживаются.
- **Не путайте порты.** 15432 и 15433 — SQL для клиентов, 5615 и 5616 — транспорт репликации. Клиент, подключившийся на порт репликации, получит `bad frameLen …`.
- **Образ Jepsen не предназначен для стенда.** Кластер собирается из `grid-sql-server-starter`, а `grid-sql-jepsen-starter` — только стенд для проверки согласованности.

## Проверка пары

```
grid://app:secret@127.0.0.1:15432,127.0.0.1:15433/public
```

Создайте таблицу и запишите строку на primary, затем прочитайте её с реплики (`readEndpoints=127.0.0.1:15433`). Состояние узлов видно через Actuator: индикатор `gridReadiness` учитывает готовность и движка, и репликации, поэтому его можно использовать как readiness-пробу.

```powershell
curl http://127.0.0.1:7777/health
curl http://127.0.0.1:7778/health
```

## Один узел без реплик

```mermaid
flowchart LR
  App[Приложение] -->|grid://_SQL| Solo["узел capacity\ndurability on\nreplication off"]
  Solo --> Disk[dataDir_OpLog_sealed]
```

Долговременное хранение и репликация — независимые переключатели. Профиль `capacity` поднимает одиночный узел с записью на диск: `grid.durability.enabled: true`, `grid.replication.enabled: false`, `fsync: true`. Это честный потолок одной машины, который не ждёт соседей, а не лабораторный режим с выключенным fsync.

```powershell
java -jar … --spring.profiles.active=capacity
```

Кольцо из трёх узлов в одном ЦОД и его поведение при отказе: [HA под нагрузкой](../configure-and-operate/operations/cluster-ha-highload.md).

## Своя конфигурация

Профили starter — демонстрационные. Для реального стенда меняют как минимум `node-id`, `cluster-id`, адреса `transport`, список `peers` и каталоги данных. Разбор ключей: [репликация](../configure-and-operate/configuration/replication.md), [долговременное хранение](../configure-and-operate/configuration/durability.md), [SQL-сервер](../configure-and-operate/configuration/sql-server.md), [Spring Boot](../develop/spring-boot.md).

Прежде чем стенд примет промышленный трафик, пройдите [чек-лист](production-checklist.md).

## Compose и несколько площадок

Готовые топологии лежат в `examples/compose/`: [развёртывание в Compose](../configure-and-operate/operations/deploy-compose.md). Образ собирается из `grid-sql-server-starter`.

Для двух площадок включите `grid.replication.cross-dc` в режиме `ASYNC_SHIP` или `SYNC_VOTERS_ACROSS_DC`: [несколько ЦОД](../configure-and-operate/operations/multi-dc.md).

## Дальше

- Отказ и передача роли пишущего узла: [повышение роли узла](../configure-and-operate/operations/ha-promote.md)
- Разгрузка чтения на реплики: [чтение с реплики](../configure-and-operate/operations/replica-reads.md)
- Метрики и пробы: [мониторинг](../configure-and-operate/monitoring.md)
- Резервные копии и восстановление на момент времени: [PITR](../configure-and-operate/operations/pitr.md)

**Связанное:** [быстрый старт](quick-start.md), [подключение клиентов](connect-clients.md), [чек-лист перед промышленной эксплуатацией](production-checklist.md).
