# Повышение роли узла

Переключение при отказе в Grid строится на ORCHID: нет выбора лидера Raft, term и vote.

## Как клиент узнаёт writer

По TCP SQL в `AUTH_OK` приходит `ServerMeta`. Если узел отклоняет запись, в ERROR тоже есть свежая мета. Пока канал жив, сервер сам шлёт `PROMOTE_NOTIFY`, когда меняются `writerEligible`, `promoteHint`, `regionEpoch` или `regionRole`. Клиент сразу обновляет мету закрепления. AUTH и ERROR остаются запасным путём после обрыва канала.

Это контракт клиента приложения: writer выбирается только из меты протокола `ServerMeta` / `PROMOTE_NOTIFY`.
Для проверок готовности оркестратора используйте Actuator `/health/liveness` и `/health/readiness` плюс Micrometer. Закрепление writer — только из меты протокола, не из HTTP.

| Поле | Смысл |
|------|--------|
| `phaseRankedProposer` | Активный writer (`min(nodeId)` среди синхронных узлов) |
| `isPhaseRankedProposer` | Этот узел может предлагать запись |
| `writerEligible` | Узел синхронен, выбран writer по фазе, с допуском записи и (при изоляции площадок) в роли Active |
| `promoteHint` | Следующий подходящий writer, если текущий недоступен |
| `maxApplyLag` | Максимальное отставание OpLog относительно applied |
| `readMode` | `read_your_writes` или `eventual_replica` |
| `maxStaleLag` | Порог отставания в операциях журнала: при превышении чтение устаревших данных не продолжается |
| `schemaEpoch` | Epoch схемы duplex (мета протокола `ServerMeta`) |
| `regionEpoch` | Epoch изоляции площадок между ЦОД (`0` — изоляция выключена / legacy) |
| `regionRole` | `NONE` / `ACTIVE` / `HOLD` / `WITNESS` |

## RPO в одном ЦОД (асинхронная отправка)

Узлы в одном ЦОД получают OpLog через асинхронную доставку журнала (Netty). После записи на primary видимость на реплике может отставать (`maxApplyLag` / отсутствующий ключ), пока apply не догонит — это **ожидаемый RPO**, не сбой Applier. См. [сеть репликации](../../understand/replication-network.md). Отставание смотрите в readiness (`applyLagStale`) и метриках; лабораторная сверка `GET /replication/compare` — не источник для выбора writer.

## Сценарий отказа пишущего узла

1. Пишущий узел (предлагающий по фазе) падает или оказывается в разделе сети.
2. Выжившие снова синхронизируют параметр порядка `R`; новый `min(nodeId)` среди достижимых синхронных узлов предлагает запись.
3. Подтягивание и HomologousRepair закрывают дыры в OpLog.
4. Живые клиенты получают `PROMOTE_NOTIFY`; переподключающиеся читают ту же `ServerMeta` через AUTH или ERROR — без ручного повышения роли как в Raft.

При `grid.replication.region.enabled=true` потеря целого Active ЦОД может поднять `regionEpoch` после успешного захвата роли Hold. Клиент обязан перепривязаться к новой epoch.

## Конфигурация клиента (падение пишущего узла)

Клиент должен попасть на узел, который **может писать** (`writerEligible=true`), а не на произвольную реплику. TCP с несколькими хостами сам по себе — только переключение транспорта, не полная схема выбора writer.

### Как работает закрепление

```mermaid
stateDiagram-v2
  [*] --> AuthOk: AUTH_OK_ServerMeta
  AuthOk --> Pinned: writerEligible_и_regionEpoch
  Pinned --> Updated: PROMOTE_NOTIFY
  Updated --> Pinned
  Pinned --> Rediscover: отказ_посреди_операции_или_fence
  Rediscover --> AuthOk: rediscoverWriter
  Pinned --> AuthOk: обрыв_канала_reconnect
```

| Правило | Поведение |
|---------|-----------|
| Ключ закрепления | `writerEligible` и `regionEpoch` (`regionEpoch=0` → только `writerEligible`) |
| Живой канал | `PROMOTE_NOTIFY` / AUTH / ERROR обновляют мету закрепления |
| Отказ посреди операции | Orchid reject или несовпадение `regionEpoch` → `rediscoverWriter()`; **не** молча крутить адрес (риск двух writer) |
| Источник | Только мета протокола `ServerMeta` |

### Один ЦОД

```
grid://u:p@n1:15432,n2:15433,n3:15434/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2
```

| Слой | Поведение |
|------|-----------|
| `RemoteConnectionFactory` multi-host | TCP с закреплением; при connect / смерти канала пробует следующий `host:port` |
| Обнаружение writer | `PROMOTE_NOTIFY` обновляет мету закрепления; AUTH / ERROR — тот же `ServerMeta` при reconnect |
| Отказ ORCHID / отставание / несовпадение площадки | **Не** переключать закрепление посреди операции. Вызывать `rediscoverWriter()`, когда прежнее закрепление теряет `writerEligible` или не совпадает `regionEpoch` |

`maxTxContexts` — потолок логических сессий на **одном** TCP, не пул из N сокетов.

### Несколько ЦОД

| Режим | Endpoints в write URL | Заметки |
|-------|------------------------|---------|
| Изоляция площадок **выкл.** (`ASYNC_SHIP` / `SYNC_VOTERS`) | Только узлы Active (primary) ЦОД | Hold / learners / remote voters — не включать в write URL |
| Изоляция площадок **вкл.** | Dual-DC URL допустим | Закрепление всё равно требует `writerEligible` и совпадение `regionEpoch`; Hold/Witness отклоняют запись |

```
# Изоляция площадок выкл. — только кольцо Active ЦОД
grid://app:secret@a1.dc-a.example:15432,a2.dc-a.example:15433,a3.dc-a.example:15434/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2

# Изоляция площадок вкл. — Active+Hold (переключение после захвата роли Hold)
grid://app:secret@a1.dc-a.example:15432,a2.dc-a.example:15433,a3.dc-a.example:15434,b1.dc-b.example:15435,b2.dc-b.example:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2

# Адреса с хоста (compose / Jepsen)
grid://app:secret@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2
```

Инвентарь адресов и YAML по ЦОД: [несколько ЦОД под нагрузкой](cluster-multidc-highload.md).

Hold / Witness / learners в URL записи без изоляции площадок дают живой TCP, но запись отклоняется (`writerEligible=false`). Отдельный путь чтения на learners/Hold должен принимать отказ при высоком отставании (`maxStaleLag` / `applyLagStale`).

Клиент закрепляется на `writerEligible` по мете протокола. Топологии и нагрузка: [HA под нагрузкой](cluster-ha-highload.md), [несколько ЦОД под нагрузкой](cluster-multidc-highload.md). Инциденты: [отказы](failures.md).

## Hold + Hold + Witness (рецепт кворума)

Топология: DC-A Active (или down) + DC-B Hold + DC-C Hold + Witness voter.  
`grid.replication.region.quorumSize` должен учитывать Hold+Witness voters для majority.

```mermaid
flowchart TB
  subgraph dcA [DC_A_Active_или_down]
    A1["узлы_A"]
  end
  subgraph dcB [DC_B_Hold]
    B1["узлы_B"]
  end
  subgraph dcC [DC_C_Hold]
    C1["узлы_C"]
  end
  subgraph wit [Witness]
    W1["w1_только_голос"]
  end
  A1 -.->|тишина| B1
  B1 -->|голос_захвата| W1
  C1 -->|голос_захвата| W1
  B1 -->|победитель_новый_Active| Client[Клиент_закрепление]
```

При потере Active: кворум захвата (`RegionClaimQuorum`) выбирает одного победителя среди Hold (+ Witness); клиент закрепляется на новом `writerEligible` без двух пишущих. Роли Active / Hold / Witness — в YAML узлов при `grid.replication.region.enabled=true`. Compose и лабораторные топологии: [развёртывание Compose](deploy-compose.md), [несколько ЦОД](multi-dc.md).

Инциденты: [отказы](failures.md).

## Связанные поверхности

Опциональное чтение с реплики (выкл. по умолчанию): [чтение с реплики](replica-reads.md). Поля readiness Actuator (`writerEligible`, `applyLagStale`, …): [мониторинг](../monitoring.md).

**Связанное:** [несколько ЦОД](multi-dc.md), [Java-клиент](../../develop/java-client.md).
