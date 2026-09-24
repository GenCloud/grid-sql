# Нагрузка JMeter

Ёмкость и задержки снимают **Apache JMeter** через `grid-sql-client` по адресу `grid://`. JDBC и синхронные обёртки (`JdbcSync`, `SyncAwait`) в нагрузочных прогонах не берут — они искажают задержки. В обычных приложениях JDBC по-прежнему нормален.

Опорные цифры и пороги регресса (~95%) — на странице [ёмкости и порогов](../performance/capacity-slo.md). Здесь описано, как запускать нагрузку и когда результат можно считать валидным.

## Что нужно

| Вопрос | Ответ |
|--------|--------|
| Чем запускать | Модуль `grid-sql-jmeter` и скрипт `scripts/run-jmeter-load-slo.ps1` |
| Куда подключаться | Порт SQL пишущего узла **15432** (пара `primary`+`replica`); какие запросы идут — задаёт смесь в плане |
| Когда результат годится | Спокойный хост, режим CLI `-n`, за раз **один** прогон |
| Где смотреть результат | Рабочие прогоны: `*-load-slo.json` в `grid-server-core/benchmarks/lab/`, HTML/CSV в `grid-sql-jmeter/target/jmeter-run/`. Опорные цифры ёмкости: [`SUMMARY.md`](../../../grid-server-core/benchmarks/results/SUMMARY.md) |

Цифры HA, которые мы заявляем, сняты на `primary` + `replica` с `fsync: true` на портах SQL **15432**/**15433**. Одиночный узел с записью на диск (профиль `capacity`) — отдельная цифра, её не смешивают с HA.

## Как запускать

1. Поднять топологию — локальную пару или compose: [запуск кластера](../getting-started/start-cluster.md), [развёртывание в compose](../configure-and-operate/operations/deploy-compose.md).
2. Убедиться, что на хосте не идут QG, Jepsen и JMH.
3. Запустить нагрузку из CLI:

```powershell
powershell -File .\scripts\run-jmeter-load-slo.ps1 -Stamp <run-id> -Clients 48 -MixProfile WRITE_ONLY
powershell -File .\scripts\run-jmeter-load-slo.ps1 -Stamp <run-id> -Clients 128 -MixProfile READ_ONLY
powershell -File .\scripts\run-jmeter-load-slo.ps1 -Stamp <run-id> -Clients 128 -MixProfile CAPACITY -Profile capacity
```

| Флаг | Смысл |
|------|-------|
| `-MixProfile` | Веса сэмплеров: `WRITE_ONLY`, `READ_ONLY`, `CAPACITY`, `CHAOS`, … |
| `-Profile` | Только `KEY_SPACE`: `capacity` (1 000 000) или `contention` (10 000) |
| `-Clients` | Число потоков; рабочий диапазон обычно 64–128 |
| `-DurationSec` | Длительность; для chaos и stress окно берут длиннее |
| `-Stamp` | Идентификатор прогона, по нему называются выходные файлы |

Отчёты: Aggregate и Summary внутри плана, HTML-дашборд (`-e -o`), CSV в `grid-sql-jmeter/target/jmeter-run/{runId}-reports/`. Машиночитаемый файл — `*-load-slo.json`.

### Режим GUI (только отладка плана)

```powershell
powershell -File .\scripts\stage-jmeter-classpath.ps1
# → grid-sql-jmeter/target/jmeter-user.classpath.txt
```

GUI требует JDK **25** с `--enable-preview`, а у `ResultCollector` должно быть заполнено поле `Filename`. Цифры из GUI годятся только для отладки плана: всё, что цитируется как опорное, снято в CLI `-n`.

## Ограничения

- Параллельный прогон с другой проверкой делает цифры недействительными — прогон выбрасывают.
- `fsync: false` изолирует узкое место в лаборатории и порогом не становится.
- JDBC и драйвер для IDE в нагрузке не используются.
- Цифры относятся к лабораторному хосту из страницы ёмкости; другая топология требует своего прогона.

## Рядом

- [Ёмкость и пороги](../performance/capacity-slo.md) — таблицы, полосы, опорные цифры
- [Методика](../performance/methodology.md) — порядок проверок и правила прерывания
- [Сводные результаты](../performance/results.md) — JMH и согласованность
- [Рекомендации](../getting-started/best-practices.md)

Английская версия: [jmeter-load-slo.md](../../en/tools/jmeter-load-slo.md).
