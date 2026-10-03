# Методика измерений

Любая цифра в разделе «Производительность» получена прогоном по правилам ниже. Прогон, который их нарушил, результатом не считается — его повторяют.

## Одна проверка за раз

Нагрузка, QG, Jepsen и JMH никогда не идут на одном хосте одновременно. После изменений в горячем пути порядок такой:

1. сводка QG;
2. Jepsen, полный прогон на одной площадке;
3. Несколько ЦОД, если затронули репликацию или отправку между площадками;
4. треки задержек JMH (`scripts/run-jmh-latency`);
5. нагрузка: WRITE_ONLY, READ_ONLY, Capacity.

Если проседают релевантные перцентили — оптимизируем код, а не сдвигаем порог. Цифры записи, чтения, QG и sizing HA работают как пороги регресса на уровне ~95% опорного значения.

## Когда прогон надо прервать

- на хосте уже идёт другой прогон или диагностическая нагрузка;
- хост шумит: фоновая сборка, индексация в IDE, подключённый профайлер;
- у результата нет идентификатора прогона в JSON или в `SUMMARY.md` — такую цифру цитировать нельзя.

## Валидно и что выбросить

| Правило | Валидно | Выбросить |
|---------|---------|-----------|
| Хост | Спокойный, без параллельных прогонов | Несколько проверок разом |
| Долговечность | `fsync: true` для порогов нагрузки | `fsync: false`, выданный за заявленный максимум |
| Идентификатор | Есть в JSON или `SUMMARY.md` | «Примерно как вчера» |

## Окружение

- Модуль: `grid-server-core`.
- Стенд задержек: `AbstractLatencyBenchmark` (`forks=1`, `threads=1`).
- Треки задержек: `scripts/run-jmh-latency.{ps1,sh}`, результат — в `grid-server-core/benchmarks/lab/`.
- Jepsen: `benchmarks/jepsen/` — дымовой `scripts/run-jepsen-smoke.{ps1,sh}`; полная матрица A–M: `scripts/run-jepsen-all-profiles.ps1` (ячейки в [`COVERAGE.md`](../../../benchmarks/jepsen/COVERAGE.md)).
- Перед допуском записи: [`wait-writer-eligible.ps1`](../../../benchmarks/jepsen/scripts/wait-writer-eligible.ps1) / `.sh` (ровно один `writerEligible=true`), не только readiness. Переменные: `WRITER_SETTLE_DEADLINE_SEC` (по умолчанию 180), `WRITER_SETTLE_POLL_SEC` (5), `POST_READY_SLEEP_SEC` (8; для Multi-DC часто **20**), `WRITER_SETTLE_HOST`.
- Векторные ядра: `--add-modules=jdk.incubator.vector` (уже задано в surefire, failsafe и в compose для Jepsen).

Нагрузку по SQL подаёт Apache JMeter поверх `grid-sql-client` (`grid://`), не JDBC. План и флаги: [JMeter](../tools/jmeter-load-slo.md). Требования к хосту: [ёмкость](capacity-slo.md).

## Треки измерений

| Класс или скрипт | Что меряет |
|------------------|------------|
| `OrchidCommitLatencyBenchmark` | долговечный коммит на одиночном узле и путь `MutationRecorder` |
| `TwoNodeOrchidCommitBenchmark` | коммит на двух узлах на localhost |
| `OpLogAppendBenchmark` | дозапись в журнал изменений (*OpLog*) с `fsync` и без него |
| `DuplexCodecBenchmark` | кодирование logical, fast и duplex |
| `ReplicaReadLatencyBenchmark` | чтение, обслуженное репликой |
| `SealedQueryPathBenchmark` | путь запроса по запечатанным файлам (*sealed*): память, гибрид, диск |
| `QueryHeavinessEstimatorBenchmark` | оценка тяжести запроса для допуска AQE |
| `ShardPartitionMapReduceBenchmark` | MapReduce по партициям шардов |
| `WireResidualBatchBenchmark` | пакетная проверка остаточных условий EQ по байтам протокола |
| `run-jepsen-smoke.{ps1,sh}` | стенд Compose на трёх узлах; дописывает строку в RESULTS |

`run-jmh-latency` последовательно прогоняет первые пять треков; остальные запускаются из JUnit (`AbstractBenchmark.runJmh`) или из JMH CLI.

## Где лежат цифры

- Сводные таблицы: [результаты](results.md).
- Файлы измерений нагрузки: [`SUMMARY.md`](../../../grid-server-core/benchmarks/results/SUMMARY.md).

Таблицы планирования руками не правят: нагрузку перезапускают на спокойном хосте и заменяют соответствующий файл.

## Jepsen

Стенд: [`benchmarks/jepsen/README.md`](../../../benchmarks/jepsen/README.md). Дымовой прогон дописывает [`RESULTS.md`](../../../benchmarks/jepsen/RESULTS.md); результаты для нескольких площадок — в [`multidc/RESULTS.md`](../../../benchmarks/jepsen/multidc/RESULTS.md). Полная матрица A–M: `run-jepsen-all-profiles.ps1` — стенд **2026-10-03** дал **13/13** safety PASS (`:valid? true`; A–K restamp **2026-10-02** был **11/11**). Профиль **J** (swarm) намеренно только append (Elle list-append; без register) — [`COVERAGE.md`](../../../benchmarks/jepsen/COVERAGE.md).

`:valid? true` говорит о согласованности. Это не пропускная способность и не порог нагрузки. Под спокойным Multi-DC `*-nochao` отказы `:no-proposer` / connect, которые Elle отбрасывает, — settle/доступность: чинить допуск записи, не ослаблять кворум/`fsync`.

Английская версия: [methodology.md](../../en/performance/methodology.md).
