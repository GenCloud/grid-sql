# Сводные результаты

Отобранные измерения Grid на лабораторном хосте: треки задержек JMH, путь запроса по запечатанным файлам (*sealed*), стоимость кодирования и коммита, проверки согласованности. Опорные цифры пропускной способности — в [ёмкости и порогах](capacity-slo.md); правила, при которых цифру можно цитировать, — в [методике](methodology.md).

## Ориентиры нагрузки

| Профиль | Полоса (лабораторный хост, `fsync: true`) |
|---------|-------------------------------------------|
| Запись HA (WRITE_ONLY) | ≈**4922**/с |
| Чтение HA (READ_ONLY) | ≈**52261…59430**/с |
| Смешанная Capacity QG | ≈**8772…11352**/с |

Условия и пороги регресса (~95%): [ёмкость](capacity-slo.md). Файлы измерений: [`SUMMARY.md`](../../../grid-server-core/benchmarks/results/SUMMARY.md).

## Запечатанный путь запроса (rows=100000)

| Бенчмарк | память | гибрид | диск | Ед. |
|----------|-------:|-------:|-----:|-----|
| pkGet | 0.306 | 0.314 | 0.244 | µs/оп |
| whereEq | 8.419 | 9.343 | 9.984 | µs/оп |
| whereEqEmpty | 0.148 | 0.140 | 2.735 | µs/оп |
| whereLike | 185.208 | 179.553 | 780.910 | µs/оп |
| whereOpenGt | 34690.861 | 34918.923 | 26871.825 | µs/оп |
| whereRange | 76.816 | 75.714 | 55.523 | µs/оп |
| zzPkMiss | 0.278 | 0.431 | 0.177 | µs/оп |

Инвариант: промах индекса не запускает скан партиции (`sealedPartitionScan` = 0).

## Кодирование и протокол

| Бенчмарк | Параметры | Score | Ед. |
|----------|-----------|------:|-----|
| `encodeCatalogLogical` | — | 110.664 | ns/оп |
| `logicalToArray` | — | 112.327 | ns/оп |
| `replHelloEncode` | direct / heap | 125.878 / 120.377 | ns/оп |
| `sqlExecEncode` | direct / heap | 2156.994 / 114.687 | ns/оп |
| `sqlWireExecProduct` | direct / heap | 167.897 / 165.022 | ns/оп |

Какой буфер где применяется: [буферы кодирования](../internal/encode-buffers.md).

## Долговечность и коммит

| Бенчмарк | Score | Ед. |
|----------|------:|-----|
| `opLogAppendFsync` | 602.621 | µs/оп |
| `opLogAppendBatch8Fsync` | 30.577 | µs/оп |
| `durableMutationRecorder` (ORCHID) | 2162.237 | µs/оп |
| `twoNodeOrchidCommit` | 217.742 | µs/оп |
| `orchidCommit` ASYNC_LOCAL / SYNC_VOTERS | 54.566 / 221.695 | µs/оп |
| `orchidCommit` с задержкой 10 мс, ASYNC / SYNC | 54.917 / 208.432 | µs/оп |
| `hierarchicalSearchTick` (оптимизатор размещения) | 561.795 | µs/оп |
| `releaseTwoShardEnvelope` | 294 728.130 | оп/с |

Разбор одной долговечной записи по стадиям: [критический путь записи ORCHID](perf-bio-consensus.md).

## Запросы и SQL

| Бенчмарк | Score | Ед. |
|----------|------:|-----|
| `gridFilterLimit` (count=100000) | 2.195 | µs/оп |
| `gridFilterOrderLimit` | 4.602 | µs/оп |
| `gridPut` (rows=10000) | 13.711 | µs/оп |
| `selectPrepared` / `selectAdHoc` | 6.571 / 20.019 | µs/оп |
| `joinPkProbe` / `joinHash` / `joinVarcharHash` | 24452.730 / 44575.460 / 49232.325 | µs/оп |
| `leftOuterJoin` / `aggregateGroupBy` | 20094.364 / 17811.354 | µs/оп |
| `withCte` / `recursiveCte` | 19164.963 / 430.979 | µs/оп |
| `selectFromView` / `materializedViewSelect` | 20174.519 / 24.489 | µs/оп |
| `selectDistinct` / `groupByHaving` / `minAggregate` | 21694.822 / 19393.674 / 18896.968 | µs/оп |
| `rowNumberWindow` / `namedRowNumberWindow` | 25203.935 / 22346.752 | µs/оп |
| `unionAll` / `intersect` / `except` | 131.761 / 131.804 / 63.708 | µs/оп |
| `scalarUdf` / `mutatingScalarUdf` / `tableUdfScan` | 47.991 / 72.090 / 496.201 | µs/оп |
| `insertReturning` / `checkConstraintInsert` | 57.988 / 52.109 | µs/оп |
| `uncontendedRecordLock` / `uncontendedTryRecordLock` | 0.283 / 0.165 | µs/оп |
| `streamSelectAll` | 3839.964 | µs/оп |

## Параллельный скан и оценка тяжести

| Бенчмарк | Параметры | Score | Ед. |
|----------|-----------|------:|-----|
| `parallelMapMerge` | count=10000 / 100000 | 284.401 / 2422.915 | µs/оп |
| `serialMapMerge` | count=10000 / 100000 | 30.512 / 289.973 | µs/оп |
| `mapReduceByShardIdentity` | count=10000 / 100000 | 251.334 / 1942.565 | µs/оп |
| `partitionByDomainShard` | count=10000 / 100000 | 111.653 / 1062.376 | µs/оп |
| `filterBlobsEq` (SIMD) | count=100000 | 2618.921 | µs/оп |
| `scalarMatchesLoop` | count=100000 | 4835.882 | µs/оп |
| `estimateEq` / `estimateAnd` / `estimateAlwaysTrue` | — | 26.881 / 60.803 / 0.350 | ns/оп |

Когда план уходит в параллельный скан: [EXPLAIN и AQE](../sql/explain-and-aqe.md).

## Упаковка запечатанных шардов

| Бенчмарк | Score | Ед. |
|----------|------:|-----|
| `packListed` | 23282.065 | оп/с |
| `fingerprintOfFiles` | 20776.436 | оп/с |
| `skipUnchangedFingerprint` | 19877.574 | оп/с |

## Согласованность

- Jepsen на одной площадке: [`benchmarks/jepsen/RESULTS.md`](../../../benchmarks/jepsen/RESULTS.md)
- Несколько площадок: [`benchmarks/jepsen/multidc/RESULTS.md`](../../../benchmarks/jepsen/multidc/RESULTS.md)
- Модельная проверка TLC: [ORCHID TLA+](../internal/orchid-tla.md)

## Рядом

- [Методика](methodology.md)
- [Ёмкость и пороги](capacity-slo.md)
- [Duplex-кодирование значений](perf-duplex.md)
- [Критический путь записи ORCHID](perf-bio-consensus.md)

Английская версия: [results.md](../../en/performance/results.md).
