# EXPLAIN and Adaptive Query Execution (AQE)

EXPLAIN text is produced only by `SqlExplainService`. Heavy SELECTs may run through Adaptive Query Execution (AQE) and a parallel map over local shards.

For operators: read EXPLAIN labels to see **why** a query is heavy — not whether the plan “broke”. Row correctness does not depend on AQE; only how work is split on the node.

Current numbers: [results](../performance/results.md), [capacity](../performance/capacity-slo.md).

## EXPLAIN

```sql
EXPLAIN SELECT * FROM t WHERE name = 'x';
```

| Node | Meaning |
|------|---------|
| `AQE_PARALLEL` | The query is admitted to a parallel chunk scan on this node |
| `DIST_MAP` | The query is admitted to a map over slices of local shards |

The planner does not dry-run a physical EXPLAIN just to estimate heaviness: `EXPLAIN` shows the chosen path, not “what another plan would have cost”.

### How to read the output

1. `AQE_PARALLEL` / `DIST_MAP` present — the heaviness check treated the query as heavy and chose a parallel path.
2. Those markers absent — ordinary local path (index or scan without AQE).
3. Correctness does not depend on the markers; only how work is split changes.

## When AQE fires

Estimation runs **before** full key materialization — from ANALYZE stats (if any) and filter / JOIN / subquery cardinality.

| Query | AQE |
|-------|-----|
| Heavy SELECT / wide JOIN / scan without a narrow index | Yes, if the estimate is above the threshold |
| EQ + LIMIT on a covering index | No — local index path |
| Many shards and a very large estimate | `DIST_MAP` — map over **local** shards on this node, not shipping SQL across the cluster |
| No ANALYZE, small estimate | No — conservatively local |

`DIST_MAP` does not mean “send the same SELECT to peers”. With consistent replication peers already hold a copy; identical-SQL fan-out to peers is off.

| Candidate estimate | Behaviour |
|--------------------|-----------|
| under **10,000** | Usually local path without AQE |
| **≥ 10,000** | Node-local AQE: parallel chunk scan |
| **≥ 50,000** and **≥ 2** shards | Partitioned map over local shards |

Index-covered residuals do not count as heavy. Without ANALYZE the estimator is conservative (local only). After keys are materialized the hot path does **not** recompute heaviness from `keys.size()`.

## Heaviness estimation (AQE threshold)

| Part | Role |
|------|------|
| `QueryHeavinessEstimator` | Estimates from ANALYZE data and filter / JOIN / subquery cardinality |
| `QueryHeaviness` | Thresholds above (10,000 / 50,000) |
| Hot path | No second heaviness pass after key materialization |

### If the admission check looks wrong

| Symptom | Check |
|---------|-------|
| No AQE on a large SELECT | ANALYZE present? Index covering the predicate (then the path is light)? |
| Too many parallel heavy queries | Concurrent heavy-worker cap (`HeavyQueryAdmission`) |
| Expected DIST_MAP, got only AQE | Domain shard count and candidate estimate |

## Node-local AQE

| Class | Role |
|-------|------|
| `AdaptiveParallelScan` | Parallel map-merge over serialized wire keys |
| `HeavyQueryAdmission` | Caps concurrent heavy workers (logic VT queue) |
| `AdaptiveChunkScheduler` | Re-splits or merges unfinished chunks mid-flight |

JOIN and filter keys stay `byte[]` up to the SPI / `SqlResult` boundary.

## Partitioned MapReduce

By default key resolution is **local**. With consistent replication, peers hold the same copy, so identical-SQL peer fan-out is not MapReduce and is disabled.

| Class | Role |
|-------|------|
| `ShardPartitionPlanner` | Buckets keys by domain shard |
| `DistributedKeyFanOut.mapReduceByShard` | Partitioned map on a heavy distributed plan |

Reduce merges disjoint wire chunks. Object maps are never used as join/filter keys.

## SIMD and residual filters

| Class | Role |
|-------|------|
| `WireResidualBatch` | Batch residual over wire blobs; simple EQ takes the SIMD path |
| `ArrayVectors` | Vector equals / compare; complex predicates fall back to scalar |

JVM: `--add-modules=jdk.incubator.vector`. Without the module, loading the Vector kernels fails.

## Query path rules

1. Keys stay binary (`byte[]` / `WireSpan`) — no `toRowMap` → re-encode for a probe.
2. No long synchronization on VT / Reactor / Netty EL.
3. SQL parsing is ANTLR only.

## Verification

- IT: `QueryHeavinessEstimatorIT`, `AdaptiveParallelScanIT`, `AdaptiveChunkSchedulerIT`, `ShardPartitionMapReduceIT`, `WireResidualBatchIT`
- JMH: `QueryHeavinessEstimatorBenchmark`, `ShardPartitionMapReduceBenchmark`, `WireResidualBatchBenchmark`

**Related:** [fundamentals](fundamentals.md), [architecture](../understand/architecture-overview.md), [capacity](../performance/capacity-slo.md).
