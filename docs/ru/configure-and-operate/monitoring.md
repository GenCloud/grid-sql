# Мониторинг

Оркестратор спрашивает: можно ли слать трафик на этот узел? Клиент спрашивает другое: кто сейчас пишущий? Первое — Actuator readiness и метрики. Второе — мета протокола (`ServerMeta` / `PROMOTE_NOTIFY`), не HTTP. См. [повышение роли узла](operations/ha-promote.md).

## Эндпоинты

В starter Actuator в корне (`management.endpoints.web.base-path: /`), `show-details: always`, группы Kubernetes-проб включены.

| Эндпоинт | Раскладка starter | Раскладка Spring по умолчанию | Назначение |
|----------|-------------------|-------------------------------|------------|
| Liveness | `/health/liveness` | `/actuator/health/liveness` | Процесс и исполнитель логики живы; перезапуск контейнера |
| Readiness | `/health/readiness` | `/actuator/health/readiness` | Можно принимать трафик |
| Metrics | `/prometheus` | `/actuator/prometheus` | Сбор Micrometer |
| Build info | `/info` | `/actuator/info` | Версия при обновлении |

Primary Actuator — **7777**, replica и capacity — **7778**. Пока репликация включена и ORCHID не синхронизирован, readiness остаётся DOWN — узел не объявляет себя готовым «заранее».

```bash
curl -s http://127.0.0.1:7777/health/readiness | jq '.components.gridReadiness.details'
# curl -s http://127.0.0.1:7778/health/readiness | jq '.components.gridReadiness.details'
```

## Детали readiness

Компонент `gridReadiness` отдаёт ключи ниже. Значение `n/a` — подсистема на узле выключена.

| Ключ | Значения | Смысл |
|------|----------|-------|
| `sqlTcp` | `listening`, `disabled`, `down` | Состояние SQL-слушателя |
| `durableOrSynced` | `up`, `n/a` | Локальная durability или sync согласия достаточны для UP |
| `orchidSynced` | `true`, `false`, `n/a` | Фазовая синхронизация ORCHID достигнута |
| `reason` | `sql_tcp_down`, `orchid_not_synced` | Только при DOWN |
| `writerEligible` | boolean, `n/a` | Узел может принимать запись |
| `applyLagStale` | boolean, `n/a` | Отставание apply выше `grid.replication.ha.max-stale-lag` |
| `orchidR` | double, `n/a` | Параметр порядка фазы `R` (насколько фазы узлов близки) |
| `repairIssued` / `repairApplied` | long, `n/a` | Счётчики ремонта пропусков |
| `rpoEstimateMs` | long, `n/a` | Оценка отставания между площадками |
| `swarmHint` | `KEEP`, `ATTRACT_LEARNER`, …, `n/a` | Подсказка размещения по имени |
| `maxTxContexts` | integer | Может появиться из YAML `grid.sql.max-tx-contexts` — **информативное** поле; Boot **не** поднимает им жёсткий потолок канала (**8**) — [SQL-сервер](configuration/sql-server.md) |
| `lockWaitTimeouts`, `lockCancels`, `sqlCancelInflight` | long | Блокировки и CANCEL |

Liveness (`gridLiveness`) отдаёт `logicExecutor`, `uptimeMs`, `pid` и те же шкалы согласия/ремонта. Ключа `reason` нет: живой процесс с несинхронизированным кластером жив, но не готов.

В Micrometer `grid.replication.swarm_hint` — **порядковый номер** (`-1`, если нет); в деталях readiness — **имя**. Не сравнивайте их напрямую.

## Метрики

Согласие и репликация:

| Метрика | Назначение |
|---------|------------|
| `grid.replication.orchid_r` | Параметр порядка фазы; держать выше `orchid.order-threshold` |
| `grid.replication.orchid_wait_p99_ns` | Ожидание фазы и кворума digest |
| `grid.replication.oplog_fsync_p99_ns` | Задержка группового `force` журнала |
| `grid.replication.repair_issued` / `repair_applied` | Ремонт пропусков; issued без applied — подтягивание застряло |
| `grid.replication.rpo_estimate_ms` | Оценка отставания между площадками |
| `grid.replication.oplog_push_sent` / `oplog_push_recv` | Объём доставки журнала |
| `grid.replication.apply_ack_sent` / `apply_ack_recv` | Подтверждения apply |
| `grid.replication.connect_failures` | Отказы connect к пирам — первый сигнал сети |
| `grid.replication.ship_backpressure` | Доставка упирается в `flow.max-inflight-ops` |
| `grid.replication.swarm_hint` | Подсказка размещения (порядковый номер) |
| `grid.replication.overlay_pinned_keys` | Живые PIN — [overlay PIN](configuration/overlay-pin.md) |

Хранение и чтение:

| Метрика | Назначение |
|---------|------------|
| `grid.replication.map_hit_rate` | Попадания в рабочий набор |
| `grid.replication.sealed_misses`, `grid.sealed.miss` | Чтения из запечатанных файлов |
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

## Диагностика задержки записи

Задержка записи делится на стадию согласия и стадию диска. Смотрите обе до смены конфигурации:

| Наблюдение | Смысл | Дальше |
|------------|--------|--------|
| Растёт `orchid_wait_p99_ns`, fsync ровный | Сеть пиров, порог фазы или нагрузка digest | `connect_failures`, RTT пиров, `orchid_r` |
| Растёт `oplog_fsync_p99_ns`, ожидание согласия ровное | Диск или ротация сегмента | Задержка устройства, `op-log.segment-size`, конкурирующий I/O |
| Обе растут | Перегруз хоста | Замер для сравнения недействителен; снизить конкуренцию и повторить |
| Растёт `ship_backpressure` | Доставка не успевает за commit | Скорость apply на пирах и `flow.max-inflight-ops` |
| Падает `map_hit_rate`, растут промахи sealed | Рабочий набор мал для трафика | Поднять `working-set-max-entries`, если хватает heap — [долговременное хранение](configuration/durability.md) |

Разбор стадий: [критический путь ORCHID](../performance/perf-bio-consensus.md).

### Ориентиры дежурства

- Готовность (`readiness`) UP — обязательное условие перед трафиком; DOWN при старте с репликацией до синхронизации — ожидаемо.
- После повышения роли: `writerEligible: true` на новом Active и клиентская мета совпали — иначе пишущий трафик на старый узел.
- Чтение с реплики: при `applyLagStale: true` не «лечить» клиентом — сначала подтягивание.
- Рост `rpo_estimate_ms` между ЦОД без инцидента — смотреть сеть и режим `ASYNC`/`SYNC`.

## Алерты

Стройте алерты по полям ниже, а не по выдуманным порогам.

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

## Типичные сигналы

| Сигнал | Смысл | Первые шаги |
|--------|--------|-------------|
| Readiness DOWN | SQL ещё не слушает или ORCHID не синхронизирован | Детали пробы (`orchidSynced`), логи старта; не слать трафик |
| `orchid_r` ниже порога | Нет допуска записи | Сеть пиров, список `peers`, нагрузка digest — [репликация](configuration/replication.md) |
| `applyLagStale: true` | Реплика слишком отстаёт для чтения | Не читать с неё; подтягивание / ремонт; порог `ha.max-stale-lag` |
| Рост отставания OpLog | Подтягивание между узлами не успевает | Сеть, диск Applier, нагрузка записи; seq на обоих. `/replication/compare` — только лаборатория |
| `writerEligible: false` после повышения роли | Клиент ещё на старом writer | `PROMOTE_NOTIFY` или `rediscoverWriter()` — [повышение роли](operations/ha-promote.md) |
| Отказ по `regionEpoch` | Клиент на устаревшей эпохе площадки | `rediscoverWriter()`, не крутить следующий host в URL |
| `repair_issued` растёт, `repair_applied` нет | Подтягивание не применяется | Логи HomologousRepair, диск, рассинхрон seq |

## Проверка после смены writer

1. На целевом writer readiness UP и `writerEligible: true`.
2. Никакой другой узел той же площадки не сообщает `writerEligible: true`.
3. Приложение увидело `PROMOTE_NOTIFY` или ту же мету при reconnect — не HTTP.
4. На репликах `applyLagStale: false` до маршрутизации чтения.
5. Одна запись и одно чтение по пути приложения проходят.

Сценарий: [повышение роли узла](operations/ha-promote.md). Топологии: [HA](operations/cluster-ha-highload.md), [несколько ЦОД](operations/multi-dc.md).

## Поверхности и их область

| Поверхность | Область |
|-------------|---------|
| `ServerMeta` и `PROMOTE_NOTIFY` по протоколу | Единственный источник для выбора пишущего клиентом |
| Actuator `/health/*` и Micrometer | Пробы оркестратора, дашборды, алерты |
| `GET /replication/compare` | Сверка lag между двумя узлами на стенде. Не обнаружение writer и не штатная HA |

Инциденты: [отказы](operations/failures.md).

## Планирование ёмкости

Ориентиры TPS на лабораторном хосте: [ёмкость и пороги](../performance/capacity-slo.md). Нагрузка JMeter: [нагрузочные прогоны](../tools/jmeter-load-slo.md). Для дежурства опирайтесь на readiness и сигналы выше, не на цифры TPS.

Дальше: [отказы](operations/failures.md), [повышение роли узла](operations/ha-promote.md), [репликация](configuration/replication.md).
