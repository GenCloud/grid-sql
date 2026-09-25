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

### Метрики Micrometer

Согласие и репликация:

| Метрика | Назначение |
|---------|------------|
| `grid.replication.orchid_r` | Параметр порядка фазы; должен держаться выше `orchid.order-threshold` |
| `grid.replication.orchid_wait_p99_ns` | Ожидание фазы и кворума digest |
| `grid.replication.oplog_fsync_p99_ns` | Задержка группового `force` журнала |
| `grid.replication.repair_issued` / `repair_applied` | Ремонт пропусков; issued без applied — подтягивание застряло |
| `grid.replication.rpo_estimate_ms` | Оценка отставания между площадками |
| `grid.replication.oplog_push_sent` / `oplog_push_recv` | Объём доставки журнала |
| `grid.replication.apply_ack_sent` / `apply_ack_recv` | Подтверждения apply |
| `grid.replication.connect_failures` | Отказы connect к пирам — первый сигнал сети |
| `grid.replication.ship_backpressure` | Доставка упирается в `flow.max-inflight-ops` |
| `grid.replication.swarm_hint` | Последняя подсказка размещения (порядковый номер) |
| `grid.replication.overlay_pinned_keys` | Живые PIN размещения — [overlay PIN](configuration/overlay-pin.md) |

Хранение и чтение:

| Метрика | Назначение |
|---------|------------|
| `grid.replication.map_hit_rate` | Попадания в рабочий набор |
| `grid.replication.sealed_misses`, `grid.sealed.miss` | Чтения из sealed-файлов |
| `grid.sealed.window_remap` | Перекладки mmap-окон на крупных payload |
| `grid.sealed.index_hit` / `index_miss` | Эффективность sealed вторичных индексов |

SQL и блокировки:

| Метрика | Назначение |
|---------|------------|
| `grid.sql.executions` | Частота операторов |
| `grid.sql.tx_commits` / `tx_rollbacks` | Исходы транзакций |
| `grid.sql.session_opens` | Открытие логических сессий |
| `grid.sql.lock.wait_acquires`, `wait_timeouts`, `wait_cancels`, `wait_nanos` | Конкуренция за блокировки записей |
| `grid.sql.cancel.requests` / `cancel.active` | Отмены клиента |
| `grid.sql.distributed.fan_in_calls` / `fan_in_sources` / `fan_in_rows` | Fan-in распределённого чтения |

### Если растёт задержка записи

Задержка записи делится на стадию согласия и стадию диска. Смотрите обе до смены конфигурации:

| Наблюдение | Смысл | Дальше |
|------------|--------|--------|
| Растёт `orchid_wait_p99_ns`, fsync ровный | Сеть пиров, порог фазы или нагрузка digest | `connect_failures`, RTT пиров, `orchid_r` |
| Растёт `oplog_fsync_p99_ns`, ожидание согласия ровное | Диск или ротация сегмента | Задержка устройства, `op-log.segment-size`, конкурирующий I/O |
| Обе растут | Перегруз хоста | Замер для сравнения недействителен; снизить конкуренцию и повторить |
| Растёт `ship_backpressure` | Доставка не успевает за commit | Скорость apply на пирах и `flow.max-inflight-ops` |
| Падает `map_hit_rate`, растут промахи sealed | Рабочий набор мал для трафика | Поднять `working-set-max-entries`, если хватает heap — [долговременное хранение](configuration/durability.md) |

Разбор стадий: [критический путь ORCHID](../performance/perf-bio-consensus.md). Как читать отставание реплики: `applyLagStale` в readiness и порог `ha.max-stale-lag` — при `true` не лечить чтением с реплики, сначала подтягивание.

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
