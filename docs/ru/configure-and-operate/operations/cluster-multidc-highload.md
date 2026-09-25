# Несколько ЦОД под нагрузкой

Межсайтовая репликация надстроена над ORCHID, который работает **внутри** ЦОД. Фазовая синхронизация (параметр `R`) по умолчанию не выходит за границы своего ЦОД. Через WAN идёт либо асинхронная отправка журнала, либо подтверждения digest от удалённых голосующих узлов.

Допуск записи между ЦОД описывается двумя вещами: ролью узла (**Active / Hold / Witness**) и монотонно растущим счётчиком **`regionEpoch`**. Клиентские записи принимает только Active. Hold может забрать роль после того, как Active замолчал, и при наборе кворума. Witness голосует, но сам не пишет.

Перед чтением: [HA в одном ЦОД под нагрузкой](cluster-ha-highload.md), [повышение роли узла](ha-promote.md), [сеть репликации](../../understand/replication-network.md).

## Роли узлов

| Роль | Принимает запись | Где обычно стоит |
|------|------------------|------------------|
| **Active** | Да, если узел ещё и `writerEligible` по ORCHID | Один ЦОД в каждый момент времени; владеет текущим `regionEpoch` |
| **Hold** | Нет. Может забрать роль после `claim-timeout-ms` тишины Active и набора кворума | Резервный ЦОД: держит тёплый OpLog и догоняет по digest |
| **Witness** | Нет, только голос при передаче роли | Третья площадка как арбитр; по умолчанию выключен и для асинхронных узлов только подтягивания не нужен |

`regionEpoch` увеличивается при успешной передаче роли на Hold. Клиент закрепляется на паре `writerEligible` и `regionEpoch`. Если посреди операции epoch разошёлся, соединение не переключают молча — вызывается `rediscoverWriter()`. Источник истины — `ServerMeta` по протоколу и `PROMOTE_NOTIFY`.

## Два режима репликации между ЦОД

| Режим | Как проходит commit | Роль удалённой стороны | Окно потерь и задержка |
|-------|---------------------|------------------------|------------------------|
| **`ASYNC_SHIP`** | Локальный ORCHID и OpLog на Active-ЦОД; журнал уходит на Hold и узлы только подтягивания асинхронно | Hold догоняет; запись на Hold и Witness отклоняется; при большом отставании или обрыве канала чтение с них тоже отклоняется, а не отдаёт старые данные | Ненулевое окно потерь на Hold; низкая задержка commit на Active. Двух Active одновременно не бывает |
| **`SYNC_VOTERS_ACROSS_DC`** | Локальный ORCHID ждёт подтверждения digest от удалённых голосующих узлов до commit | Узлы из `cross-dc.voters` (или все удалённые, кроме `learners`) подтверждают синхронно; `learners` — только асинхронно | Окно потерь на голосующих узлах близко к нулю, но за каждую запись платим задержкой WAN |

Две настройки, о которых стоит знать заранее:

- `cross-dc.phase-coupling` — по умолчанию **выключено**. Включает учёт фаз удалённых узлов в локальном `R`. Это независимо от голосования по digest; для согласованности между ЦОД предпочтительнее именно голосующие узлы.
- `remote-ack-timeout-ms` (по умолчанию `5000`) — потолок ожидания удалённых digest и, если включён `require-remote-ack`, подтверждения применения.

Witness включается точечно: `grid.replication.region.role: WITNESS` на узле, который участвует в кворуме передачи роли, но запись не принимает. Асинхронные узлы только подтягивания (`learners`) без изоляции площадок остаются узлами только подтягивания, с `write-admission: false` (запись не допускается).

## Топология Active + Hold

Опорный вариант — один Active-ЦОД (кольцо адресов записи) и один Hold-ЦОД (горячий резерв). Роль задаётся на узле в `grid.replication.region.role` (`ACTIVE`, `HOLD`, `WITNESS`). На старте все узлы одного ЦОД имеют одну роль.

### Пример адресов (3+2)

| Узел | ЦОД | Роль | SQL (DNS) | SQL (проброс на хост) | Репликация |
|------|-----|------|-----------|------------------------|------------|
| `a1` | `dc-a` | **ACTIVE** | `a1.dc-a.example:15432` | `127.0.0.1:15432` | `a1.dc-a.example:5615` |
| `a2` | `dc-a` | **ACTIVE** | `a2.dc-a.example:15433` | `127.0.0.1:15433` | `a2.dc-a.example:5616` |
| `a3` | `dc-a` | **ACTIVE** | `a3.dc-a.example:15434` | `127.0.0.1:15434` | `a3.dc-a.example:5617` |
| `b1` | `dc-b` | **HOLD** | `b1.dc-b.example:15435` | `127.0.0.1:15435` | `b1.dc-b.example:5618` |
| `b2` | `dc-b` | **HOLD** | `b2.dc-b.example:15436` | `127.0.0.1:15436` | `b2.dc-b.example:5619` |

Необязательный Witness: например `w1.dc-w.example:15437`, репликация `5620`, `region.role: WITNESS`, без полезной нагрузки OpLog. См. схему Witness ниже и [повышение роли узла](ha-promote.md).

### ASYNC_SHIP — карта узлов

```mermaid
flowchart LR
  subgraph dcA [DC_A_Active]
    A1["a1_writer"]
    A2["a2"]
    A3["a3"]
  end
  subgraph dcB [DC_B_Hold]
    B1["b1_Hold"]
    B2["b2_Hold"]
  end
  Client[Клиент] -->|SQL_только_Active| A1
  A1 <-->|Netty_внутри_ЦОД| A2
  A1 <-->|Netty_внутри_ЦОД| A3
  A1 -.->|журнал_async_WAN| B1
  A1 -.->|журнал_async_WAN| B2
```

Commit на Active локальный; Hold догоняет. Окно потерь на Hold ненулевое.

### SYNC_VOTERS — карта узлов

```mermaid
flowchart LR
  subgraph dcA2 [DC_A_Active]
    S1["a1_writer"]
    S2["a2"]
    S3["a3"]
  end
  subgraph dcB2 [DC_B_voters]
    V1["b1_voter"]
    L1["b2_learner_async"]
  end
  Client2[Клиент] -->|SQL_Active| S1
  S1 <-->|Netty_внутри_ЦОД| S2
  S1 <-->|Netty_внутри_ЦОД| S3
  S1 ==>|digest_ACK_до_commit| V1
  S1 -.->|только_async| L1
```

Commit ждёт digest от голосующих узлов за WAN (`remote-ack-timeout-ms`). Узлы только подтягивания (`cross-dc.learners`) остаются асинхронными и **не** обслуживают клиентский SQL.

Стартовый YAML. На всех узлах один и тот же `epoch`, пока передача роли его не поднимет:

```yaml
# на a1 / a2 / a3
grid.replication.region:
  enabled: true
  role: ACTIVE
  epoch: 1
  claim-timeout-ms: 5000
  quorum-size: 2

# на b1 / b2
grid.replication.region:
  enabled: true
  role: HOLD
  epoch: 1
  claim-timeout-ms: 5000
  quorum-size: 2
```

На Hold-узлах дополнительно `cross-dc.write-admission: false` (запись с удалённой площадки не допускается), пока роль не изменилась. После успешной передачи новый Active владеет `regionEpoch+1`. Ожившая бывшая Active-площадка переходит в Hold по данным HELLO и меты — она **никогда** не становится Active просто потому, что снова включилась.

### Строки подключения клиента

| Сценарий | Строка подключения |
|----------|--------------------|
| Запись, изоляция площадок включена (адреса обоих ЦОД) | `grid://app:secret@a1.dc-a.example:15432,a2.dc-a.example:15433,a3.dc-a.example:15434,b1.dc-b.example:15435,b2.dc-b.example:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2` |
| То же, порты проброшены на хост (Compose, Jepsen) | `grid://app:secret@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2` |
| Только кольцо Active-ЦОД (изоляция площадок выключена) | `grid://app:secret@a1.dc-a.example:15432,a2.dc-a.example:15433,a3.dc-a.example:15434/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2` |
| Чтение с Hold, если приложение готово к отказу при отставании | `grid://app:secret@b1.dc-b.example:15435,b2.dc-b.example:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2` |

Что происходит при отказах:

- Падение `a1` внутри Active-ЦОД — закрепление и `promoteHint` уводят клиента на `a2` или `a3`, `regionEpoch` не меняется.
- Потеря всего Active-ЦОД — Hold забирает роль и поднимает `regionEpoch`. Клиент через `rediscoverWriter()` закрепляется на новом Active в DC-B. Молча ротировать соединение посреди операции нельзя: [повышение роли узла](ha-promote.md).

```mermaid
sequenceDiagram
  participant A as Active_DC_A
  participant H as Hold_DC_B
  participant W as Witness_opt
  participant C as Client
  A--xH: DC_A_down
  H->>W: claim_vote
  H->>H: quorum_ok regionEpoch_plus_1
  H->>C: PROMOTE_NOTIFY_new_epoch
  C->>C: rediscoverWriter
  C->>H: SQL_на_новом_Active
  Note over A,H: "бывший Active оживает как Hold не как Active"
```

## Как журнал уходит между ЦОД

```mermaid
sequenceDiagram
  participant M as MutationRecorder
  participant O as OrchidNode
  participant X as CrossDcPublisher
  participant V as Remote_voter
  participant L as Remote_Hold_learner
  Note over M,O: "Local R and digest - SYNC waits remote voter digests"
  M->>O: appendAndWaitCommit
  O-->>M: committed
  M->>X: ship complete TX units only
  alt ASYNC_SHIP
    X-->>V: OPLOG_PUSH async
    X-->>L: OPLOG_PUSH async
  else SYNC_VOTERS_ACROSS_DC
    X-->>V: OPLOG_PUSH to voters
    X-->>L: async learners only
  end
  Note over X: "TxEnvelopeCoordinator - без отправки грязной середины TX unit"
```

`CrossDcPublisher` собирает операции в пачки (`batch-max-ops`, `batch-max-wait-ms`), буферизует открытые транзакции до `TX_COMMIT` или `TX_ABORT` и придерживает многошардовые транзакции, пока не зафиксируются все шарды. Наружу уходят только целые транзакции — незавершённые изменения через WAN не отправляются. Транспорт всегда Netty: [сеть репликации](../../understand/replication-network.md).

## Чем платим за низкое окно потерь

```mermaid
flowchart TB
  subgraph asyncMode [ASYNC_SHIP]
    W1[Write commit Active] --> S1[Ship WAN async]
    S1 --> R1[Hold apply lag = RPO]
  end
  subgraph syncMode [SYNC_VOTERS_ACROSS_DC]
    W2[Write] --> D2[Wait remote digest ACK]
    D2 --> C2[Commit and ship]
    C2 --> R2[Voters closer to zero RPO]
    D2 -.->|RTT_timeout| Tax[WAN latency on critical path]
  end
```

| Что сравниваем | `ASYNC_SHIP` | `SYNC_VOTERS_ACROSS_DC` |
|----------------|--------------|--------------------------|
| Задержка commit (p50/p99) | Локальный ORCHID и fsync OpLog | То же плюс задержка туда‑обратно до удалённых узлов, потолок — `remote-ack-timeout-ms` |
| Что теряем при падении удалённого ЦОД | Всё, что не успело доехать и примениться | На голосующих узлах почти ничего; узлы только подтягивания по-прежнему асинхронны |
| Обрыв канала между ЦОД | Active продолжает писать; Hold отклоняет запись и отклоняет устаревшее чтение | Commit встаёт или завершается ошибкой, пока голосующие узлы недоступны |
| Измеренная цена на лабораторном хосте | — | Около 8× к задержке относительно асинхронного режима на стенде JMH; цифры: [ёмкость и пороги](../../performance/capacity-slo.md) |

Отставание реплик **внутри** одного ЦОД разбирается в [повышении роли узла](ha-promote.md).

## Рецепты YAML

### Изоляция площадок

```yaml
grid:
  replication:
    region:
      enabled: true
      role: ACTIVE          # ACTIVE | HOLD | WITNESS
      epoch: 1              # стартовый epoch, >= 1
      claim-timeout-ms: 5000
      quorum-size: 2        # сколько Hold/Witness должны подтвердить передачу роли
```

На Hold и Witness — соответственно `role: HOLD` или `role: WITNESS` и тот же стартовый `epoch`. При `enabled: false` действует прежняя схема с фиксированным основным ЦОД и флагом `write-admission` (допуск записи).

### `ASYNC_SHIP` — пропускная способность важнее нулевого окна потерь

```yaml
grid:
  durability:
    enabled: true
    hydrate-mode: LAZY
    working-set-max-entries: 2_000_000
  sql:
    default-shards: 16
  sql-server:
    enabled: true
    port: 15432
  replication:
    enabled: true
    node-id: a1
    cluster-id: prod-multi
    orchid:
      coupling: 15.0
      natural-freq-hz: 1.0
      order-threshold: 0.85
      tick-ms: 10
      digest-quorum: MAJORITY
    transport:
      bind-port: 5615
      peers:
        - { id: a2, host: a2.dc-a, port: 5615, dc: dc-a }
        - { id: a3, host: a3.dc-a, port: 5615, dc: dc-a }
        - { id: b1, host: b1.dc-b, port: 5615, dc: dc-b }
        - { id: b2, host: b2.dc-b, port: 5615, dc: dc-b }
    region:
      enabled: true
      role: ACTIVE
      epoch: 1
      claim-timeout-ms: 5000
      quorum-size: 2
    cross-dc:
      enabled: true
      local-dc: dc-a
      mode: ASYNC_SHIP
      phase-coupling: false
      remote-ack-timeout-ms: 5000
      batch-max-ops: 64
      batch-max-wait-ms: 20
      require-remote-ack: false
      voters: []
      learners: [b1, b2]
      write-admission: true
    op-log:
      data-dir: ./data/a1/replication
      fsync: true
```

### `SYNC_VOTERS_ACROSS_DC` — меньше окно потерь, платим задержкой WAN

```yaml
    cross-dc:
      enabled: true
      local-dc: dc-a
      mode: SYNC_VOTERS_ACROSS_DC
      phase-coupling: false
      remote-ack-timeout-ms: 5000
      batch-max-ops: 64
      batch-max-wait-ms: 20
      require-remote-ack: false
      voters: [b1]
      learners: [b2]
```

Если в режиме `SYNC_VOTERS_ACROSS_DC` список `voters` пуст, голосующими считаются все удалённые узлы, кроме перечисленных в `learners`.

## Чем проверять

| Контур | Состояние |
|--------|-----------|
| Jepsen N=3 в одном ЦОД | [benchmarks/jepsen/README.md](../../../../benchmarks/jepsen/README.md) |
| Jepsen между ЦОД: оба режима, обрыв канала, убийство удалённого голосующего узла, потеря всего Active-ЦОД | [RESULTS.md](../../../../benchmarks/jepsen/multidc/RESULTS.md) |
| Формальная модель-компаньон | `spec/orchid/OrchidLogMultiDc` — дополнение к Jepsen, не замена |
| JMH: цена синхронного режима против асинхронного | [ёмкость и пороги](../../performance/capacity-slo.md), [критический путь ORCHID](../../performance/perf-bio-consensus.md) |

Опорные цифры нагрузки: [ёмкость и пороги](../../performance/capacity-slo.md). Итоги согласованности — в ссылках Jepsen выше и на [сводных результатах](../../performance/results.md). Не гонять проверки согласованности вместе с нагрузкой: [методика](../../performance/methodology.md).

**Связанное:** [несколько ЦОД](multi-dc.md), [Compose-развёртывание](deploy-compose.md), [повышение роли узла](ha-promote.md), [настройка репликации](../configuration/replication.md).
