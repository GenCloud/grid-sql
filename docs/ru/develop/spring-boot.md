# Spring Boot

Grid встраивается в Spring Boot двумя разными способами, и их важно не путать.

| Роль процесса | Что подключаете | Что получаете |
|---------------|-----------------|---------------|
| **Узел СУБД** | `grid-sql-server-starter` (толстый jar) или зависимость на `grid-server-core` | Движок, каталог, хранение, репликация, приём SQL по TCP |
| **Приложение-клиент** | `grid-sql-client` | `ConnectionFactory` по `grid://`; движок в процесс не тянется |

Типовое приложение — второй случай: сервис не хостит базу, он к ней подключается.

Автоконфигурация лежит в `grid-server-core`: `GridAutoConfiguration` (ядро и хранение), `GridSqlAutoConfiguration` (`SqlServerRuntime` + приём SQL по TCP), `GridReplicationAutoConfiguration` (ORCHID, OpLog, пиры). Все они включаются от префикса `grid.*`.

## Запуск узла

```powershell
mvn -o -pl grid-sql-server-starter -am package -DskipTests
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=primary
```

Точка входа — `org.genfork.grid.sql.server.GridSqlServerStarter`. Если Spring не нужен вообще (запуск из IDE, минимальный стенд), есть `org.genfork.grid.sql.SqlServerMain` без контекста Boot.

### Профили starter

| Профиль | SQL | Репликация | HTTP | Назначение |
|---------|----:|-----------:|-----:|------------|
| `primary` | **15432** | **5615** | 7777 | узел демо-пары, который может писать (`writerEligible`) |
| `replica` | **15433** | **5616** | 7778 | Второй узел той же пары |
| `capacity` | **15432** | — | 7778 | Одиночный узел с записью на диск (`replication.enabled: false`, `fsync: true`) |

`capacity` — честный потолок одного узла: локальная запись на диск включена, ожидания пиров нет. Это не «режим без репликации с выключенным fsync».

## Конфигурация узла

Автоконфигурация читает ключи `grid.*` ниже. YAML — **образец профиля starter `primary`**, а не голые значения по умолчанию из `GridConfigurationProperties`.

| Параметр | Default библиотеки (без профиля) | Типичный `primary` / `capacity` |
|----------|----------------------------------|----------------------------------|
| `durability.hydrate-mode` | `FULL` | `LAZY` |
| `durability.working-set-max-entries` | `0` (без потолка) | например `262144` |
| `replication.op-log.segment-size` | `1024` (МиБ) | например `64` |
| `replication.ha.replica-reads-enabled` | `false` | `true` на демо `primary`/`replica` |

Полные таблицы defaults: [долговременное хранение](../configure-and-operate/configuration/durability.md), [репликация](../configure-and-operate/configuration/replication.md), [SQL-сервер](../configure-and-operate/configuration/sql-server.md). Ключи архива PITR (`oplog-archive.*`) и multi-site `region.*` — на тех страницах; по умолчанию выключены.

```yaml
grid:
  sql:
    default-shards: 8
    data-dir: ./data-primary/catalog
  sql-server:
    enabled: true
    host: 0.0.0.0
    port: 15432
  codec:
    duplex:
      enabled: true
      schema-epoch: 1
  overlay:
    enabled: false
    durable: false
  durability:
    enabled: true
    hydrate-mode: LAZY            # профиль; в библиотеке по умолчанию FULL
    working-set-max-entries: 262144
    adaptive-disk-first: true
    # oplog-archive.enabled: false   # включить до нагрузки, которую может понадобиться откатить — см. PITR
  replication:
    enabled: true
    node-id: primary-1
    cluster-id: example-grid
    ha:
      max-stale-lag: 10000
      replica-reads-enabled: true
    orchid:
      coupling: 15.0
      natural-freq-hz: 1.0
      order-threshold: 0.85
      tick-ms: 10
      digest-quorum: MAJORITY
      max-propose-in-flight: 64
    transport:
      bind-host: 127.0.0.1
      bind-port: 5615
      connect-timeout-ms: 5000
      peers:
        - { id: replica-1, host: 127.0.0.1, port: 5616, dc: dc-a }
    op-log:
      data-dir: ./data-primary/replication
      segment-size: 64
      fsync: true
    repair:
      homologous-enabled: true
      reconcile-interval-ms: 5000
    swarm:
      enabled: true
      score-window-ms: 5000
      migrate-threshold: 0.3
    cross-dc:
      enabled: true
      local-dc: dc-a
      mode: ASYNC_SHIP            # ASYNC_SHIP | SYNC_VOTERS_ACROSS_DC
      batch-max-ops: 256
      batch-max-wait-ms: 20
    flow:
      max-inflight-ops: 10000
```

Смысл групп:

| Группа | Смысл |
|--------|-------|
| `grid.sql` | Каталог и шардирование: `default-shards` — число шардов по умолчанию для новых таблиц, `data-dir` — дерево каталога и запечатанных файлов |
| `grid.sql-server` | Приём SQL по TCP. Включайте **только** на серверных узлах; приложению-клиенту он не нужен |
| `grid.durability` | Локальная запись на диск: OpLog + запечатанные GMAP. Работает и без реплик (одиночный узел) |
| `grid.replication` | Обмен с пирами: ORCHID, отправка журнала, подтягивание, размещение. Отдельный выключатель от `durability` |
| `grid.replication.op-log.fsync` | `true` для любых заявляемых цифр. `false` — только лаборатория |
| `grid.replication.ha` | Допустимое отставание реплики и разрешение читать с неё |
| `grid.replication.cross-dc` | Доставка между площадками |

Два независимых выключателя — самая частая путаница:

- `grid.durability.enabled: true` + `grid.replication.enabled: false` — один узел, данные переживают перезапуск, реплик нет.
- `grid.durability.enabled: true` + `grid.replication.enabled: true` — плюс отправка журнала на пиры, кворум дайджестов, подтягивание.

`dataDir` — **на каждый узел**. Один каталог на два процесса или общий NFS/SAN на кластер не поддерживается.

Подробности по ключам — [долговременное хранение](../configure-and-operate/configuration/durability.md), [репликация](../configure-and-operate/configuration/replication.md), [SQL-сервер](../configure-and-operate/configuration/sql-server.md).

## Наблюдаемость

Профили starter отдают Actuator по отдельному HTTP-порту:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
      base-path: /
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,gridReadiness
```

`gridReadiness` и `gridLiveness` — индикаторы состояния узла: readiness учитывает готовность движка и репликации, поэтому в Kubernetes его можно повесить на пробу готовности. Метрики — [мониторинг](../configure-and-operate/monitoring.md).

## Приложение как клиент

В сервисном приложении заводится обычный бин фабрики и переиспользуется на весь процесс:

```java
@Configuration
public class GridClientConfiguration {

    @Bean(destroyMethod = "dispose")
    public ConnectionFactory gridConnectionFactory(
            @Value("${app.grid.url}") String url) {
        return ConnectionFactory.fromUrl(url);
    }
}
```

```yaml
app:
  grid:
    url: grid://app:secret@127.0.0.1:15432,127.0.0.1:15433/public?maxTxContexts=64&connectTimeoutMs=1000
spring:
  threads:
    virtual:
      enabled: true
```

Правила для клиентской стороны:

- **Не** включайте `grid.sql-server.enabled` и не тащите `grid-server-core` в каждый сервис — движок не должен подниматься в процессе приложения.
- Одна фабрика на процесс (или на соседний узел), а не фабрика на запрос.
- Чтения с реплик: в URL клиента — `readEndpoints`; на **сервере** ещё `grid.replication.ha.replica-reads-enabled: true` (в library по умолчанию выключено; профили starter `primary`/`replica` включают). См. [чтение с реплики](../configure-and-operate/operations/replica-reads.md).
- Не блокируйте реактивные цепочки внутри сервиса; `.block()` — только на границе приложения. См. [Java-клиент](java-client.md).

## Локальная пара и дальше

Поднять 1+1 на одной машине — [запуск кластера](../getting-started/start-cluster.md). Compose-топологии — [развёртывание в Compose](../configure-and-operate/operations/deploy-compose.md).

**Связанное:** [Java-клиент](java-client.md), [транзакции](transactions.md), [SQL-сервер](../configure-and-operate/configuration/sql-server.md), [долговременное хранение](../configure-and-operate/configuration/durability.md).
