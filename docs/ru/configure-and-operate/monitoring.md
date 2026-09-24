# Мониторинг

Узел отдаёт своё состояние двумя способами: HTTP-эндпоинты Spring Actuator и метрики Micrometer. Выбор пишущего узла клиентом сюда не относится — это делается по протоколу, см. [повышение роли узла](operations/ha-promote.md).

## Эндпоинты Actuator

В рабочем starter Actuator вынесен в корень: `management.endpoints.web.base-path: /`. (Профили лабораторных стендов могут делать то же.)

| Эндпоинт | Назначение |
|----------|------------|
| `/health/liveness` (проба `gridLiveness`) | Процесс жив, исполнитель логики отвечает. Используется в healthcheck Docker |
| `/health/readiness` (проба `gridReadiness`) | Узел готов принимать трафик: SQL TCP слушает и, при включённой репликации, узел синхронизирован по ORCHID |
| `/prometheus` | Сбор метрик Micrometer |
| `/info` | Сведения о сборке |

Если base-path оставлен по умолчанию (`/actuator`), те же группы доступны как `/actuator/health/liveness` и `/actuator/health/readiness`.

Профили starter: primary Actuator на **7777**, replica и capacity — на **7778**. Пример:

```bash
curl -s http://127.0.0.1:7777/health/readiness
# curl -s http://127.0.0.1:7778/health/readiness
```

Пока узел не синхронизировался по ORCHID, readiness остаётся DOWN. Трафик на него не пойдёт: узел не объявляет себя готовым в расчёте на то, что синхронизация подтянется позже.

### Если растёт p99 записи

Сначала разделите стадии горячего пути. Метрики собирает `ReplicationMetrics` (часто видны в деталях health / Micrometer):

| Метрика | Стадия | Если растёт |
|---------|--------|-------------|
| `orchidWaitP50Ns` / `orchidWaitP99Ns` | Ожидание ORCHID (фаза + digest) | Сеть пиров, порог `R`, нагрузка на digest |
| `oplogFsyncP50Ns` / `oplogFsyncP99Ns` | Групповой `force` OpLog при `fsync: true` | Диск, размер сегмента, конкуренция `force` |

Рост только ORCHID — сеть или порог `R`. Рост только fsync — диск. Обе сразу — хост перегружен, такие цифры в пороги не годятся. Разбор стадий: [критический путь ORCHID](../performance/perf-bio-consensus.md).

Как читать отставание реплики: поле `applyLagStale` в readiness и порог `ha.max-stale-lag` — при `true` не лечить чтением с реплики, сначала подтягивание.

### Что видно в деталях пробы

При `show-details: always` проба отдаёт не только `status`, но и поля ниже.

| Поле | Откуда берётся |
|------|----------------|
| `sqlTcp` | SQL TCP слушает (`listening` / `disabled` / down) |
| `durableOrSynced` | Долговечность или ORCHID sync достаточны для UP |
| `reason` | Краткая причина DOWN (`sql_tcp_down`, `orchid_not_synced`, …) |
| `writerEligible`, `orchidSynced`, `applyLagStale` | `ReplicationCoordinator` |
| `orchidR` | `OrchidNode` / `ReplicationNodeState` |
| `maxTxContexts` | Может появиться, если в YAML задан `grid.sql.max-tx-contexts`; Boot **не** применяет его к TCP-слушателю (жёсткий потолок канала остаётся **8**) — [SQL-сервер](configuration/sql-server.md) |
| `lockWaitTimeouts`, `lockCancels`, `sqlCancelInflight` | Счётчики блокировок и CANCEL |
| `repairIssued`, `repairApplied` | `HomologousRepair` |
| `rpoEstimateMs` | `CrossDcMetrics` |
| `swarmHint` | Имя подсказки размещения или `n/a` (`AdaptiveReplicaSwarm`) |

В Micrometer метрика `grid.replication.swarm_hint` — **порядковый номер** (`-1`, если нет); в деталях readiness — **имя** подсказки. Не сравнивайте их как одно и то же.

### Метрики Micrometer

| Метрика | Что означает |
|---------|--------------|
| `grid.replication.orchid_r` | Параметр порядка `R`: насколько узлы сошлись по фазе |
| `grid.replication.repair_issued` и `repair_applied` | Счётчики запрошенных и применённых восстановлений |
| `grid.replication.rpo_estimate_ms` | Оценка отставания удалённого ЦОД в миллисекундах |
| `grid.replication.swarm_hint` | Порядковый номер последней подсказки по размещению (`-1`, если подсказки нет) |

Для дежурства: `orchid_r` не должен проваливаться ниже порога допуска; `rpo_estimate_ms` не должен расти без причины; `repair_issued` без `repair_applied` — сигнал проблем с подтягиванием.

### Ориентиры дежурства

- Готовность (`readiness`) UP — обязательное условие перед трафиком; DOWN при старте с репликацией до синхронизации — ожидаемо.
- После повышения роли: `writerEligible: true` на новом Active и клиентская мета совпали — иначе пишущий трафик на старый узел.
- Чтение с реплики: при `applyLagStale: true` не «лечить» клиентом — сначала подтягивание.
- Рост `rpo_estimate_ms` между ЦОД без инцидента — смотреть сеть и режим `ASYNC`/`SYNC`.

### На что ставить алерт

Стройте алерты по полям ниже, а не по выдуманным числовым SLO. Ориентиры нагрузки — [ёмкость](../performance/capacity-slo.md).

| Условие | Почему важно | Тяжесть |
|---------|--------------|---------|
| Readiness DOWN после окна прогрева | Узел не должен принимать трафик | Page |
| Ни один узел не сообщает `writerEligible: true` | Нет допуска записи | Page |
| Два узла с `writerEligible: true` на разных площадках | Риск двух писателей | Page; [несколько ЦОД](operations/multi-dc.md) |
| `orchid_r` ниже `orchid.order-threshold` длительно | Запись будет отклоняться | Page |
| `applyLagStale: true` на узле, с которого читают | Чтения отклоняются или устарели | Ticket, затем разбор lag |
| `repair_issued` растёт, `repair_applied` стоит | Подтягивание застряло | Ticket |
| Растёт `oplog_fsync_p99_ns` / диск почти полный | Путь долговечности тормозит | Page |
| Растёт `rpo_estimate_ms` без известного сетевого события | Растёт окно потерь между ЦОД | Ticket |
| Растут `lock.wait_timeouts` | Клиенты получают ошибки блокировок | Ticket |

## Разбор типичных сигналов

| Сигнал | Смысл | Первые шаги |
|--------|--------|-------------|
| `readiness` DOWN | SQL ещё не слушает или ORCHID не синхронизирован | Смотреть детали пробы: `orchidSynced`, логи старта; не слать трафик |
| `orchid_r` ниже порога | Нет допуска записи | Проверить сеть пиров, список `peers`, нагрузку на digest; [репликация](configuration/replication.md) |
| `applyLagStale: true` | Реплика слишком отстаёт для чтения | Не читать с неё; подтягивание / ремонт; порог `ha.max-stale-lag` |
| Рост отставания OpLog | Подтягивание между узлами не успевает | Сеть, диск Applier, нагрузка записи; seq на обоих. Сверка `/replication/compare` — только лаборатория, не штатная HA |
| `writerEligible: false` после повышения роли | Клиент ещё на старом writer | Ждать `PROMOTE_NOTIFY` или `rediscoverWriter()` — [повышение роли](operations/ha-promote.md) |
| Отказ по `regionEpoch` | Клиент на устаревшей эпохе площадки | `rediscoverWriter()`, не крутить следующий host в URL |
| `repair_issued` растёт, `repair_applied` нет | Подтягивание не применяется | Логи HomologousRepair, диск, рассинхрон seq |

## Что смотреть при смене writer

1. На новом узле `gridReadiness` = UP и в деталях `writerEligible: true`.
2. Клиент получил `PROMOTE_NOTIFY` или при reconnect увидел ту же мету в AUTH/ERROR — не HTTP.
3. Если `applyLagStale: true` на реплике, чтение с неё отклоняется (при включённом чтении с реплики).

Пошаговый сценарий: [повышение роли узла](operations/ha-promote.md). Топологии: [HA](operations/cluster-ha-highload.md), [несколько ЦОД](operations/multi-dc.md).

## Диагностика репликации

| Поверхность | Для чего годится |
|-------------|------------------|
| `ServerMeta` и `PROMOTE_NOTIFY` по протоколу | Единственный источник для выбора пишущего узла клиентом |
| Actuator `/health/*` и метрики Micrometer | Проверки готовности для оркестратора и сбор метрик |
| `GET /replication/compare` | **Лаборатория.** Сверка lag между двумя узлами на стенде. Не источник истины для штатной HA и не обнаружение writer |

Инциденты по симптомам: [отказы](operations/failures.md).

## Планирование ёмкости

Ориентиры TPS на лабораторном хосте: [ёмкость и пороги](../performance/capacity-slo.md). Как снимать нагрузку JMeter: [нагрузочные прогоны](../tools/jmeter-load-slo.md). Для дежурства опирайтесь на readiness и симптомы выше, не на цифры TPS.

**Связанное:** [отказы](operations/failures.md), [повышение роли узла](operations/ha-promote.md), [настройка репликации](configuration/replication.md), [HA в одном ЦОД](operations/cluster-ha-highload.md).
