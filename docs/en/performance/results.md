# Performance results

Curated Grid measurements from the lab host: JMH latency tracks, the sealed query path, encode and commit costs, and consistency checks. Throughput planning figures live in [capacity](capacity-slo.md); the rules that make a run citable are in [methodology](methodology.md).

## Load planning figures

| Profile | Band (lab host, `fsync: true`) |
|---------|--------------------------------|
| HA write (WRITE_ONLY) | ≈**4922**/s |
| HA read (READ_ONLY) | ≈**52261…59430**/s |
| Mixed Capacity QG | ≈**8772…11352**/s |

Conditions and the ~95% regression floors: [capacity](capacity-slo.md). Measurement files: [`SUMMARY.md`](../../../grid-server-core/benchmarks/results/SUMMARY.md).

## Sealed query path (rows=100000)

| Benchmark | memory | hybrid | disk | Unit |
|-----------|-------:|-------:|-----:|------|
| pkGet | 0.306 | 0.314 | 0.244 | µs/op |
| whereEq | 8.419 | 9.343 | 9.984 | µs/op |
| whereEqEmpty | 0.148 | 0.140 | 2.735 | µs/op |
| whereLike | 185.208 | 179.553 | 780.910 | µs/op |
| whereOpenGt | 34690.861 | 34918.923 | 26871.825 | µs/op |
| whereRange | 76.816 | 75.714 | 55.523 | µs/op |
| zzPkMiss | 0.278 | 0.431 | 0.177 | µs/op |

Invariant: an index miss never starts a partition scan (`sealedPartitionScan` = 0).

## Encode and wire

| Benchmark | Params | Score | Unit |
|-----------|--------|------:|------|
| `encodeCatalogLogical` | — | 110.664 | ns/op |
| `logicalToArray` | — | 112.327 | ns/op |
| `replHelloEncode` | direct / heap | 125.878 / 120.377 | ns/op |
| `sqlExecEncode` | direct / heap | 2156.994 / 114.687 | ns/op |
| `sqlWireExecProduct` | direct / heap | 167.897 / 165.022 | ns/op |

Which buffer goes where: [encode buffers](../internal/encode-buffers.md).

## Durability and commit

| Benchmark | Score | Unit |
|-----------|------:|------|
| `opLogAppendFsync` | 602.621 | µs/op |
| `opLogAppendBatch8Fsync` | 30.577 | µs/op |
| `durableMutationRecorder` (ORCHID) | 2162.237 | µs/op |
| `twoNodeOrchidCommit` | 217.742 | µs/op |
| `orchidCommit` ASYNC_LOCAL / SYNC_VOTERS | 54.566 / 221.695 | µs/op |
| `orchidCommit` 10 ms delay, ASYNC / SYNC | 54.917 / 208.432 | µs/op |
| `hierarchicalSearchTick` (placement optimizer) | 561.795 | µs/op |
| `releaseTwoShardEnvelope` | 294728.130 | ops/s |

Stage-by-stage breakdown of one durable write: [ORCHID write path](perf-bio-consensus.md).

## Query and SQL

| Benchmark | Score | Unit |
|-----------|------:|------|
| `gridFilterLimit` (count=100000) | 2.195 | µs/op |
| `gridFilterOrderLimit` | 4.602 | µs/op |
| `gridPut` (rows=10000) | 13.711 | µs/op |
| `selectPrepared` / `selectAdHoc` | 6.571 / 20.019 | µs/op |
| `joinPkProbe` / `joinHash` / `joinVarcharHash` | 24452.730 / 44575.460 / 49232.325 | µs/op |
| `leftOuterJoin` / `aggregateGroupBy` | 20094.364 / 17811.354 | µs/op |
| `withCte` / `recursiveCte` | 19164.963 / 430.979 | µs/op |
| `selectFromView` / `materializedViewSelect` | 20174.519 / 24.489 | µs/op |
| `selectDistinct` / `groupByHaving` / `minAggregate` | 21694.822 / 19393.674 / 18896.968 | µs/op |
| `rowNumberWindow` / `namedRowNumberWindow` | 25203.935 / 22346.752 | µs/op |
| `unionAll` / `intersect` / `except` | 131.761 / 131.804 / 63.708 | µs/op |
| `scalarUdf` / `mutatingScalarUdf` / `tableUdfScan` | 47.991 / 72.090 / 496.201 | µs/op |
| `insertReturning` / `checkConstraintInsert` | 57.988 / 52.109 | µs/op |
| `uncontendedRecordLock` / `uncontendedTryRecordLock` | 0.283 / 0.165 | µs/op |
| `streamSelectAll` | 3839.964 | µs/op |

## Parallel scan and heaviness estimate

| Benchmark | Params | Score | Unit |
|-----------|--------|------:|------|
| `parallelMapMerge` | count=10000 / 100000 | 284.401 / 2422.915 | µs/op |
| `serialMapMerge` | count=10000 / 100000 | 30.512 / 289.973 | µs/op |
| `mapReduceByShardIdentity` | count=10000 / 100000 | 251.334 / 1942.565 | µs/op |
| `partitionByDomainShard` | count=10000 / 100000 | 111.653 / 1062.376 | µs/op |
| `filterBlobsEq` (SIMD) | count=100000 | 2618.921 | µs/op |
| `scalarMatchesLoop` | count=100000 | 4835.882 | µs/op |
| `estimateEq` / `estimateAnd` / `estimateAlwaysTrue` | — | 26.881 / 60.803 / 0.350 | ns/op |

When a plan goes parallel: [EXPLAIN and AQE](../sql/explain-and-aqe.md).

## Sealed shard packing

| Benchmark | Score | Unit |
|-----------|------:|------|
| `packListed` | 23282.065 | ops/s |
| `fingerprintOfFiles` | 20776.436 | ops/s |
| `skipUnchangedFingerprint` | 19877.574 | ops/s |

## Consistency

- Jepsen 1-DC: [`benchmarks/jepsen/RESULTS.md`](../../../benchmarks/jepsen/RESULTS.md)
- Multi-DC: [`benchmarks/jepsen/multidc/RESULTS.md`](../../../benchmarks/jepsen/multidc/RESULTS.md)
- TLC model check: [ORCHID TLA+](../internal/orchid-tla.md)

## Related

- [Methodology](methodology.md)
- [Capacity and thresholds](capacity-slo.md)
- [Duplex value encoding](perf-duplex.md)
- [ORCHID write critical path](perf-bio-consensus.md)

Russian: [results.md](../../ru/performance/results.md).
