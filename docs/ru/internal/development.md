# Правила разработки

Инженерные правила и команды сборки для тех, кто дорабатывает сам Grid.

## Базовые правила

- Не жертвовать горячим путём, силой кворума и долговечностью `fsync` ради того, чтобы проверка прошла; вместо этого чинить узкое место.
- Исходники — UTF-8 без BOM. Никаких `import pkg.*` и литералов в теле метода, только именованные константы.
- В исполнителях запросов остаёмся на байтах протокола (`byte[]`, `WireSpan`), без декодирования в `Object` посреди конвейера.
- SQL разбирает только ANTLR; ручного сопоставления ключевых слов в ядре нет.
- Без долгой синхронизации на виртуальных потоках, планировщиках Reactor и event loop Netty; без `.block()` в API библиотеки.
- Новый функционал на горячем пути выходит вместе с треком JMH или прогоном нагрузки.
- После существенных изменений проверки гоняют по одной на спокойном хосте: QG → Jepsen на одной площадке → несколько площадок (если затронуто) → JMH → нагрузка.

## Требования

- Java **25** с `--enable-preview`
- Maven 3.9+ (`project.build.sourceEncoding=UTF-8`)
- Модуль ядра: `grid-server-core`
- Векторным ядрам нужен `--add-modules=jdk.incubator.vector`: surefire, failsafe и compose для Jepsen его уже задают, продуктовая JVM должна задавать тоже
- Допуск AQE опирается на `QueryHeavinessEstimator` (sidecar от ANALYZE, без пробного EXPLAIN) — см. [EXPLAIN и AQE](../sql/explain-and-aqe.md)

## Сборка и тесты

```bash
mvn -pl grid-server-core -am test
mvn -pl grid-server-core -Dtest="index.unit.replication.chaos.**" test
```

Chaos-тесты безопасности используют `ReplTestSupport.safetyProps` с реальным `order-threshold`, а не с `0.0`.

## TLC (ORCHID)

```bash
./scripts/run-tlc-orchid.sh
pwsh ./scripts/run-tlc-orchid.ps1
```

Используется кэш `tla2tools.jar` в `.tools/`. Нужны обе спецификации: `OrchidLog.tla` и `OrchidLogMultiDc.tla`. Подробности: [ORCHID TLA+](orchid-tla.md).

Цепочка perf-гейта (QG, Jepsen, несколько площадок, JMH, нагрузка) — на **спокойном хосте, по одной проверке**, не как default GitHub Actions. Unit CI — `.github/workflows/ci.yml`; матрица Jepsen/QG/Multi-DC — `jepsen-qg.yml` (см. корневой [README](../../README.md) § CI). Локальный драйвер:

```bash
./scripts/run-perf-gate.sh
pwsh ./scripts/run-perf-gate.ps1
```

## JMH

Классы бенчмарков лежат в `src/test/java/index/benchmarks/` и запускаются из JUnit (`AbstractBenchmark.runJmh`) или из JMH CLI. Треки задержек гоняет `scripts/run-jmh-latency.{ps1,sh}`, результат пишется в `grid-server-core/benchmarks/lab/`.

Условия: [методика](../performance/methodology.md). Текущие цифры: [сводные результаты](../performance/results.md).

## Starter-модули

```bash
mvn -pl grid-sql-server-starter -am package
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar

# узлы для Jepsen
mvn -pl grid-sql-jepsen-starter -am package
```

Статус репликации для эксплуатации выставлен наружу через Actuator health и Micrometer.

## Рядом

- [Обзор архитектуры](../understand/architecture-overview.md)
- [Журнал дефектов](bug-journal.md)
- [Ёмкость и пороги](../performance/capacity-slo.md)
- [Чтение с реплик](../configure-and-operate/operations/replica-reads.md) и [смена пишущего узла](../configure-and-operate/operations/ha-promote.md)

Английская версия: [development.md](../../en/internal/development.md).
