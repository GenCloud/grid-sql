# Методика измерений

Любая цифра в разделе «Производительность» получена прогоном по правилам ниже. Прогон, который их нарушил, результатом не считается — его повторяют.

## Одна проверка за раз

Нагрузка, QG, Jepsen и JMH никогда не идут на одном хосте одновременно. После изменений в горячем пути порядок такой:

1. сводка QG;
2. Jepsen, полный прогон на одной площадке;
3. Multi-DC, если затронули репликацию или отправку между площадками;
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
- Jepsen: `benchmarks/jepsen/` плюс `scripts/run-jepsen-smoke.{ps1,sh}`.
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

Стенд: [`benchmarks/jepsen/README.md`](../../../benchmarks/jepsen/README.md). Дымовой прогон дописывает [`RESULTS.md`](../../../benchmarks/jepsen/RESULTS.md); результаты для нескольких площадок — в [`multidc/RESULTS.md`](../../../benchmarks/jepsen/multidc/RESULTS.md).

`:valid? true` говорит о согласованности. Это не пропускная способность и не порог нагрузки.

Английская версия: [methodology.md](../../en/performance/methodology.md).
