# Development

Engineering rules and build commands for contributors working on Grid itself.

## Ground rules

- Never trade away hot-path performance, quorum strength or `fsync` durability to make a check pass; fix the bottleneck instead.
- Sources are UTF-8 without BOM. No `import pkg.*`, no magic literals in method bodies, named constants only.
- Executors stay on wire bytes (`byte[]`, `WireSpan`); no decode to `Object` mid-pipeline.
- SQL is parsed by ANTLR only. No hand-rolled keyword matching in the engine.
- No long synchronization on virtual threads, Reactor schedulers or the Netty event loop; no `.block()` in library API.
- New hot-path features ship with a JMH track or a load run.
- After substantial changes run checks one at a time on a calm host: QG → Jepsen 1-DC → Multi-DC (if touched) → JMH → load.

## Requirements

- Java **25** with `--enable-preview`
- Maven 3.9+ (`project.build.sourceEncoding=UTF-8`)
- Core module: `grid-server-core`
- Vector kernels need `--add-modules=jdk.incubator.vector`; surefire, failsafe and the Jepsen compose already set it, and the product JVM must set it too
- AQE admission uses `QueryHeavinessEstimator` (ANALYZE sidecar, no EXPLAIN dry-run) — see [EXPLAIN and AQE](../sql/explain-and-aqe.md)

## Build and test

```bash
mvn -pl grid-server-core -am test
mvn -pl grid-server-core -Dtest="index.unit.replication.chaos.**" test
```

Safety chaos tests use `ReplTestSupport.safetyProps` with a real `order-threshold`, not `0.0`.

## TLC (ORCHID)

```bash
./scripts/run-tlc-orchid.sh
pwsh ./scripts/run-tlc-orchid.ps1
```

Uses the cached `tla2tools.jar` under `.tools/`. Both specs are required: `OrchidLog.tla` and `OrchidLogMultiDc.tla`. Details: [ORCHID TLA+](orchid-tla.md).

The performance gate chain (QG, Jepsen, Multi-DC, JMH, load — calm host, one at a time):

```bash
./scripts/run-perf-gate.sh
pwsh ./scripts/run-perf-gate.ps1
```

## JMH

Benchmark classes live under `src/test/java/index/benchmarks/` and run either from JUnit (`AbstractBenchmark.runJmh`) or from the JMH CLI. Latency tracks run through `scripts/run-jmh-latency.{ps1,sh}` and write into `grid-server-core/benchmarks/lab/`.

Conditions: [methodology](../performance/methodology.md). Current numbers: [results](../performance/results.md).

## Starters

```bash
mvn -pl grid-sql-server-starter -am package
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar

# Jepsen nodes
mvn -pl grid-sql-jepsen-starter -am package
```

Replication status for operators is exposed through Actuator health and Micrometer.

## Related

- [Architecture overview](../understand/architecture-overview.md)
- [Bug journal](bug-journal.md)
- [Capacity and thresholds](../performance/capacity-slo.md)
- [Replica reads](../configure-and-operate/operations/replica-reads.md) and [promote a node](../configure-and-operate/operations/ha-promote.md)

Russian: [development.md](../../ru/internal/development.md).
