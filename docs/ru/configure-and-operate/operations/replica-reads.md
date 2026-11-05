# Чтение с реплики

Клиент хочет разгрузить пишущий узел SELECT-ами. По умолчанию всё идёт на пишущий узел. Можно явно включить: запись и TX — на пишущий узел; autocommit SELECT / EXPLAIN — на реплики с допустимым отставанием. Лучше отказ при устаревших данных, чем «тихо прочитать старое».

## Топология

```mermaid
flowchart TB
  App[Приложение]
  App -->|запись_TX_DDL_PREPARE_FOR_UPDATE| Writer["кольцо_пишущих\nPRIMARY"]
  App -->|SELECT_EXPLAIN| Reads["readEndpoints\nREAD_REPLICA"]
  Writer --> N1["n1_пишущий"]
  Writer --> N2["n2"]
  Reads --> R1["реплика_1"]
  Reads --> R2["реплика_N"]
  CatchUpOnly["только_подтягивание_или_Witness"] -.->|нет_клиентского_SQL| X[отказ]
```

Узлы без обслуживания клиентов (обучающиеся / learners) и Witness клиентский SQL не обслуживают.

| Путь | Куда | Роль сессии | Допуск |
|------|------|-------------|--------|
| `begin`, DML, DDL, PREPARE, FOR UPDATE | кольцо пишущих | `PRIMARY` | узел с `writerEligible`, epoch совпадает, данные не устарели |
| `createStatement` read-only SELECT/EXPLAIN | `readEndpoints` | `READ_REPLICA` | ANTLR-маршрут клиента + допуск сервера |
| `createReadStatement` / `executeRead` | `readEndpoints` | `READ_REPLICA` | явный API |

### FOR UPDATE и блокировки на пирах

`FOR UPDATE` / `SKIP LOCKED` выполняются только на **пишущем** узле (не на реплике только для чтения). Индексные ключи в сериализованных байтах блокируются локально (`LockAwareKeyCursor`). При включённой репликации и непустом списке пиров при старте выставляются Netty-агенты из **`peers` репликации** (`SqlServerRuntime` → `ReplicationCoordinator.createNettyDistForUpdatePeerLockAgents()`); пиры берут те же блокировки через `DistForUpdateCoordinator`. Голоса фазы prepare несут **набор ключей на пира**; аренда на соседе истекает через **30 с** (без отдельного журнала prepare — долговечность через журнал транзакций OpLog). Ошибка Netty на соседнем узле — **отказ клиенту**, а не тихий commit только на локальном узле. В autocommit аренда блокировки на соседе снимается после оператора; в открытой транзакции — до COMMIT/ROLLBACK. Prepare и commit-dec — облегчённый двухфазный протокол по блокировкам в кадрах продукта, не внешний XA. Поддерживаются несколько таблиц и INNER JOIN.

Без репликации или при пустом списке `peers` блокировки только локальные. Отдельного YAML-ключа со списком адресов DistForUpdate нет. Не путать с `grid.sql.distributed-peers` (разлёт только чтения SELECT/JOIN) — [SQL-сервер](../configuration/sql-server.md).

### Авто-маршрут (v2)

Если в URL есть `readEndpoints`, `ConnectionFactory.fromUrl` возвращает маршрутизирующее соединение. Классификация SQL — через общий ANTLR `SqlRouteClassifier`:

- **READ** (обычный SELECT / EXPLAIN без блокировок) → реплика с наименьшим числом незавершённых запросов
- **WRITE** / TX / DDL / PREPARE / `FOR UPDATE` → пишущий

Поддерживается несколько `readEndpoints`. Собирать две фабрики вручную не нужно.

Чтобы закрепить текущий поток на пишущем (Flyway / DDL на URL с репликами) — `SqlClientRouteContext.forcePrimary()` / `runWithPrimary` / `callWithPrimary`: [Java-клиент](../../develop/java-client.md).

Серверный допуск (`SqlStatementTag`) отклоняет недопустимые операции на сессиях `READ_REPLICA`.

## Конфиг сервера

### Включить чтение с реплики

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

### Пишущий узел + одна реплика (локально)

Профили стартера `application-primary.yml` / `application-replica.yml` включают чтение с реплики для локального стенда 1+1. В библиотеке по умолчанию `replica-reads-enabled: false`.

| Узел | Профиль | SQL | Репликация | Каталог данных |
|------|---------|-----|------------|----------------|
| пишущий | `primary` | `:15432` | `:5615` | `./data-primary/...` |
| реплика | `replica` | `:15433` | `:5616` | `./data-replica/...` |

Узлы перекрёстно в `grid.replication.transport.peers`. У каждого узла свой `dataDir`.

## Конфиг клиента

### URL записи (закрепление на пишущем)

```
grid://user:pass@127.0.0.1:15432,127.0.0.1:15433/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2
```

Кольцо адресов — для обнаружения пишущего при отказе (`PROMOTE_NOTIFY` / AUTH `ServerMeta`). Отказ посреди операции → `rediscoverWriter()`.

### URL чтения (N адресов)

Приложение **обязано** перечислить SQL-адреса узлов кластера в authority и в `readEndpoints`.
`createReadFactory` строит кольцо `readEndpoints` ∪ адреса authority и распределяет autocommit SELECT/EXPLAIN
по правилу «наименьшее число незавершённых запросов» среди всех **не устаревших** членов кольца
(предлагающий запись тоже в кольце). В v1 нет динамического состава из Orchid — только явный список в URL.
После `applyLagStale` адрес помечается устаревшим, и трафик уходит на остальные.

```
grid://user:pass@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433,127.0.0.1:15434&readPreference=REPLICA&maxReadConnections=6
```

| Параметр | Смысл |
|----------|--------|
| `readEndpoints` | Список `host:port` для чтения (N≥1 при `readPreference=REPLICA`); для разгрузки пишущего укажите все синхронизированные избиратели |
| `readPreference` | `PRIMARY` (по умолчанию) или `REPLICA` |
| `maxReadConnections` | Потолок TCP пула чтения (≥ размера кольца, иначе баланс схлопнется на один адрес) |
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

## Отставание, полнота данных и отказы

Одного serving-stream `applyLagStale` для допуска чтения **недостаточно**. Клиентский SQL на реплике fail-closed также при **неполных** данных.

```mermaid
flowchart TB
  Req["Оператор на READ_REPLICA"] --> A{"replicaReadsEnabled\nи не catch-up/Witness?"}
  A -->|нет| DenyCfg["REPLICA_READ_DISABLED / отказ"]
  A -->|да| B{"applyLagStale\nили дыра каталога?"}
  B -->|да| Stale["REPLICA_READ_STALE / ротация"]
  B -->|нет| C{"structural sealed hole\nна локальных файлах?"}
  C -->|да| Inc["incomplete / ротация"]
  C -->|нет| D{"sticky missing-stream\nMissingStreamAdmitState?"}
  D -->|да / индекс не готов| Inc
  D -->|нет| Exec["execute → staging → map → sealed"]
```

*Схема 2. Допуск раньше I/O строки. Sticky missing-stream — O(1); пересборка inventory на диске — вне пути SELECT.*

### Два трека: admit fence и differential heal

| Трек | Триггер | Влияние на SELECT |
|------|---------|-------------------|
| **Admit fence** | `applyLagStale`, дыра `catalogFingerprint`, **локальная** structural sealed hole, proposer **missing-stream** (sticky) | Отказ / ротация / fallback на writer |
| **Differential heal** | FP inventory пира ≠ локальный индекс по `domain#shard` | Pull mismatched shards (`fromSeq=1`); **сам** admit не закрывает |
| **Aggregate root FP** | Один смешанный FP всех sealed pack | Метрики / реклама HELLO — **никогда** не fence admit в одиночку |

Sticky missing-stream (`MissingStreamAdmitState` + `LocalSealedInventoryIndex`):

1. Seal / unpack / invalidate structural hole → inventory **not ready** (fail-closed) и enqueue rebuild на `helloCatchUp`.
2. Rebuild один раз обходит диск → memory index и пересчёт sticky bit (`ProposerMissingStreamUtil`).
3. Дальнейшие SELECT читают sticky bit — **без** directory-walk `ofShardIdentity` на запрос.
4. Пока индекс не готов — admit incomplete.

| Условие | Поведение |
|---------|-----------|
| `applyLagStale` (serving streams) | `REPLICA_READ_STALE` → AUTH-ротация по кольцу чтения |
| Непроигранный LAZY tip / неполный apply | Тот же клиентский сигнал incomplete — не тихий пустой SELECT |
| Несовпадение `catalogFingerprint` с пирами | Отказ: неполный object-set каталога (дыра VIEW/TABLE) |
| Structural hole sealed (magic-OK undersize / битый GMAP) на локальных файлах | Отказ: lag-complete ≠ sealed-complete. Aggregate root FP и mismatch peer inventory **сами по себе** не admit fence (inventory только differential heal). HELLO рекламирует per-shard identity; integrity tick тянет только mismatched shards (`fromSeq=1`). Readiness `applyLagStale` **не** закрывает этот fence. |
| Proposer missing-stream (только follower) | В inventory phase-ranked proposer есть `domain#shard` с FP≠0, а локальные tip/applied OpLog = 0 **и** локальный sealed identity = 0 → отказ (incomplete). Предикат считается при rebuild inventory в sticky bit — не на каждый SELECT с диска. Tip-lag-complete ≠ key/stream-complete. Оба FP=0 и seal-dump lag при `lastSeq>0` — **не** incomplete. Layout drift (оба FP≠0, но разные) — только heal. |
| Inventory index не готов | Fail-closed incomplete, пока `helloCatchUp` не опубликует индекс |
| `replicaReadsEnabled=false` | `REPLICA_READ_DISABLED` |
| Узел только подтягивания / learner | отказ клиентского SQL |
| Witness | отказ чтения с реплики |
| DML / BEGIN на `READ_REPLICA` | `READ_REPLICA_DML_DENIED` (после тега ANTLR) |

**Авто-fallback на пишущий:** при `readPreference=REPLICA`, если кольцо чтения исчерпано с сигналом incomplete/stale (`ReplicaReadFallbackUtil`), `RoutingConnection` / Sync resume на **writer**, а не отдают тихий пустой результат. Явный `forcePrimary` по-прежнему пинит writer. В wire `ServerMeta` может быть `catalogFingerprint` (HELLO/AUTH).

В v1 нет политики `ALLOW_STALE`. После своей записи читайте с пишущего или смиритесь с возможным отставанием на реплике.

## Плюсы и риски

| Плюс | Риск |
|------|------|
| Разгрузка пишущего от SELECT | При отставании — отказ и ротация адреса |
| Авто-маршрут через общий ANTLR | `SqlRouteClassifier` должен совпадать с серверными тегами |
| Явный API чтения доступен | Приложение может ошибочно звать `executeRead` для записи |
| Jepsen остаётся только на PRIMARY | URL с `readEndpoints` в Elle отклоняется |
| Hold может отдавать чтение при нормальном отставании | Witness никогда не отдаёт |
| На Windows уплотнение (seal) снимает mmap `.sbpt` перед REPLACE | Уплотнение всё равно конкурирует с дисковым вводом-выводом под нагрузкой |

## Поверхности API

| Поверхность | Статус |
|-------------|--------|
| `ConnectionFactory.fromUrl` + ANTLR авто-маршрут | готово (рекомендуется) |
| `createReadStatement` / `executeRead` | готово (явный API) |
| `RemoteConnectionFactory.createReadFactory` | готово (каналы к репликам) |
| `staleReadPolicy` | v1: только отказ при устаревших данных |
| N `readEndpoints` + ротация на incomplete/stale + fallback на writer | готово (product `RoutingConnection` / `ReplicaReadFallbackUtil`) |
| Баланс по незавершённым запросам на не устаревшем кольце (N≥3) | готово (`ReadEndpointSelector` + IT баланса) |

Дальше: [отказы](failures.md), [повышение роли узла](ha-promote.md), [сеть репликации](../../understand/replication-network.md).

## Jepsen

`JepsenSqlClient` отклоняет URL с `readEndpoints` (контракт Elle — только PRIMARY). Проверки согласованности и нагрузку не гоняйте вместе на одном хосте — см. [методику](../../performance/methodology.md).
