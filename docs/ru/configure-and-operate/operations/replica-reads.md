# Чтение с реплики

По умолчанию выключено: все запросы идут на primary. Можно явно включить маршрутизацию: запись — на пишущий узел; autocommit SELECT и EXPLAIN — на синхронные реплики (и Hold при допустимом отставании).

## Топология

```mermaid
flowchart TB
  App[Приложение]
  App -->|WRITE_TX_DDL_PREPARE_FOR_UPDATE| Writer["кольцо_пишущих\nPRIMARY"]
  App -->|SELECT_EXPLAIN| Reads["readEndpoints\nREAD_REPLICA"]
  Writer --> N1["n1_writer"]
  Writer --> N2["n2"]
  Reads --> R1["replica_1"]
  Reads --> R2["replica_N"]
  CatchUpOnly["catch_up_or_Witness"] -.->|нет_клиентского_SQL| X[отказ]
```

Узлы без обслуживания клиентов (`learners`) и Witness клиентский SQL не обслуживают.

| Путь | Куда | Роль сессии | Допуск |
|------|------|-------------|--------|
| `begin`, DML, DDL, PREPARE, FOR UPDATE | кольцо пишущих | `PRIMARY` | узел с `writerEligible`, epoch совпадает, не stale |
| `createStatement` read-only SELECT/EXPLAIN | `readEndpoints` | `READ_REPLICA` | ANTLR-маршрут клиента + допуск сервера |
| `createReadStatement` / `executeRead` | `readEndpoints` | `READ_REPLICA` | явный API |

### FOR UPDATE и блокировки на пирах

`FOR UPDATE` / `SKIP LOCKED` выполняются только на **пишущем** узле (не на реплике только для чтения). Индексные ключи в сериализованных байтах блокируются локально (`LockAwareKeyCursor`). При включённой репликации и непустом списке пиров Boot ставит Netty-агенты из **`peers` репликации** (`SqlServerRuntime` → `ReplicationCoordinator.createNettyDistForUpdatePeerLockAgents()`); пиры берут те же блокировки через `DistForUpdateCoordinator`. Ошибка Netty на пире — отказ при ошибке. В autocommit аренда блокировки на пире снимается после оператора; в открытой TX — до COMMIT/ROLLBACK. Prepare/commit-dec — облегчённый обмен голосами по блокировкам в кадрах продукта, не XA. Поддерживаются multi-table и INNER JOIN.

Без репликации или при пустом списке peers блокировки только локальные. Отдельного YAML-ключа со списком DistForUpdate endpoints нет. Не путать с `grid.sql.distributed-peers` (разлёт только чтения SELECT/JOIN) — [SQL-сервер](../configuration/sql-server.md).

### Авто-маршрут (v2)

Если в URL есть `readEndpoints`, `ConnectionFactory.fromUrl` возвращает маршрутизирующее соединение. Классификация SQL — через общий ANTLR `SqlRouteClassifier`:

- **READ** (обычный SELECT / EXPLAIN без блокировок) → реплика с наименьшим числом незавершённых запросов
- **WRITE** / TX / DDL / PREPARE / `FOR UPDATE` → пишущий

Поддерживается несколько `readEndpoints`. Собирать две фабрики вручную не нужно. Серверный допуск (`SqlStatementTag`) отклоняет недопустимые операции на сессиях `READ_REPLICA`.

## Конфиг сервера

```yaml
grid:
  durability:
    enabled: true
    hydrate-mode: LAZY
  replication:
    enabled: true
    ha:
      max-stale-lag: 10000
      replica-reads-enabled: true
```

Профили starter `application-primary.yml` / `application-replica.yml` включают чтение с реплики для локального стенда 1+1. В library по умолчанию `replica-reads-enabled: false`.

| Узел | Профиль | SQL | Репликация | dataDir |
|------|---------|-----|------------|---------|
| primary | `primary` | `:15432` | `:5615` | `./data-primary/...` |
| replica | `replica` | `:15433` | `:5616` | `./data-replica/...` |

Узлы перекрёстно в `grid.replication.transport.peers`. У каждого узла свой `dataDir`.

## Конфиг клиента

### URL записи (закрепление на пишущем)

```
grid://user:pass@127.0.0.1:15432,127.0.0.1:15433/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2
```

Кольцо адресов — для обнаружения writer при отказе (`PROMOTE_NOTIFY` / AUTH `ServerMeta`). Отказ посреди операции → `rediscoverWriter()`.

### URL чтения (N адресов)

```
grid://user:pass@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433,127.0.0.1:15434&readPreference=REPLICA&maxReadConnections=2
```

| Параметр | Смысл |
|----------|--------|
| `readEndpoints` | Список `host:port` для чтения (N≥1 при `readPreference=REPLICA`) |
| `readPreference` | `PRIMARY` (по умолчанию) или `REPLICA` |
| `maxReadConnections` | Потолок TCP для фабрики чтения (по умолчанию 1) |
| `staleReadPolicy` | v1: только отказ при устаревших данных |

### Фабрики

```java
ConnectionFactory factory = ConnectionFactory.fromUrl(url);
Connection conn = factory.obtain().block();
conn.createStatement("SELECT ...").execute();   // может уйти на реплику
conn.createStatement("INSERT ...").execute();   // всегда пишущий

((RoutingConnection) conn).executeRead("SELECT ...");
RemoteConnectionFactory reads = RemoteConnectionFactory.createReadFactory(url);
```

`SESSION_OPEN` передаёт роль сессии (`SessionRoleWire` v2). `PROMOTE_NOTIFY` роль сессии не меняет.

## Отставание и отказы

| Условие | Поведение |
|---------|-----------|
| `applyLagStale` | `REPLICA_READ_STALE` → смена адреса на следующем `obtain()` / границе оператора |
| `replicaReadsEnabled=false` | `REPLICA_READ_DISABLED` |
| Узел только подтягивания / learner | отказ клиентского SQL |
| Witness | отказ чтения с реплики |
| DML / BEGIN на `READ_REPLICA` | `READ_REPLICA_DML_DENIED` (после тега ANTLR) |

В v1 нет политики `ALLOW_STALE` (только отказ при stale). После своей записи читайте с пишущего узла или смиритесь с возможным отставанием на реплике.

## Плюсы и риски

| Плюс | Риск |
|------|------|
| Разгрузка пишущего от SELECT | При отставании — отказ и ротация адреса |
| Авто-маршрут через общий ANTLR | `SqlRouteClassifier` должен совпадать с серверными тегами |
| Явный read API доступен | Приложение может ошибочно звать `executeRead` для записи |
| Jepsen остаётся PRIMARY-only | URL с `readEndpoints` в Elle отклоняется |
| Hold может отдавать чтение при нормальном отставании | Witness никогда не отдаёт |
| На Windows seal снимает mmap `.sbpt` перед REPLACE | Seal всё равно конкурирует с IO под нагрузкой |

## Поверхности API

| Поверхность | Статус |
|-------------|--------|
| `ConnectionFactory.fromUrl` + ANTLR авто-маршрут | готово (рекомендуется) |
| `createReadStatement` / `executeRead` | готово (явный API) |
| `RemoteConnectionFactory.createReadFactory` | готово (пул чтения) |
| `staleReadPolicy` | v1 только отказ при stale |
| N `readEndpoints` + ротация на `applyLagStale` | готово (`ReplicaReadEndpointsRotateIT`) |

## Связанное

- [отказы](failures.md)
- [повышение роли узла](ha-promote.md)
- [HA под нагрузкой](cluster-ha-highload.md)
- [сеть репликации](../../understand/replication-network.md)

## Jepsen

`JepsenSqlClient` отклоняет URL с `readEndpoints` (контракт Elle — только PRIMARY). Проверки согласованности и нагрузку не гоняйте вместе на одном хосте — см. [методику](../../performance/methodology.md).
