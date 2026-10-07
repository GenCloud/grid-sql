# Benchmarks methodology

Every number in the Performance section comes from a run that follows the rules below. A run that breaks them is not a result — repeat it.

## One check at a time

Load, QG, Jepsen and JMH never run together on one host. After a hot-path change the order is:

1. QG summary;
2. Jepsen, full 1-DC;
3. Multi-site, if replication or cross-site shipping was touched;
4. JMH latency tracks (`scripts/run-jmh-latency`);
5. load: WRITE_ONLY, READ_ONLY, Capacity.

If the relevant percentiles fail, optimize the code instead of moving the threshold. The WRITE, READ, QG and HA sizing figures are regression floors at ~95% of the planning number.

## When to abort a run

- another run or a diagnostic load is already on the host;
- the host is noisy — background build, IDE indexing, profiler attached;
- the result carries no run id in the JSON or in `SUMMARY.md`; such a number cannot be cited.

## Valid / discard

| Rule | Valid | Discard |
|------|-------|---------|
| Host | Calm, no parallel runs | Several checks at once |
| Durability | `fsync: true` for load thresholds | `fsync: false` presented as a claimed maximum |
| Run id | Id in the JSON or `SUMMARY.md` | “Roughly like yesterday” |

## Environment

- Module: `grid-server-core`
- Latency harness: `AbstractLatencyBenchmark` (`forks=1`, `threads=1`)
- Latency tracks: `scripts/run-jmh-latency.{ps1,sh}`, output under `grid-server-core/benchmarks/lab/`
- Heavy AQE map-merge: JUnit JMH `index.benchmarks.AdaptiveParallelScanBenchmark` (calm host; compare parallel vs serial at ≥10k keys)
- Jepsen: `benchmarks/jepsen/` — smoke `scripts/run-jepsen-smoke.{ps1,sh}`; full A–M matrix `scripts/run-jepsen-all-profiles.ps1` (cells in [`COVERAGE.md`](../../../benchmarks/jepsen/COVERAGE.md))
- Settle before admitting write traffic: [`wait-writer-eligible.ps1`](../../../benchmarks/jepsen/scripts/wait-writer-eligible.ps1) / `.sh` (exactly one `writerEligible=true`), not readiness alone. Knobs: `WRITER_SETTLE_DEADLINE_SEC` (default 180), `WRITER_SETTLE_POLL_SEC` (5), `POST_READY_SLEEP_SEC` (8; Multi-DC often **20**), `WRITER_SETTLE_HOST`.
- Vector kernels: `--add-modules=jdk.incubator.vector` (already set in surefire/failsafe and in the Jepsen compose)

SQL load comes from Apache JMeter over `grid-sql-client` (`grid://`), not JDBC. Plan and flags: [JMeter](../tools/jmeter-load-slo.md). Host rules: [capacity](capacity-slo.md).

## Benchmark tracks

| Class / script | Measures |
|----------------|----------|
| `OrchidCommitLatencyBenchmark` | solo durable commit and the `MutationRecorder` path |
| `TwoNodeOrchidCommitBenchmark` | commit across two nodes on localhost |
| `OpLogAppendBenchmark` | OpLog append with and without `fsync` |
| `DuplexCodecBenchmark` | logical, fast and duplex encoding |
| `ReplicaReadLatencyBenchmark` | read served from a replica |
| `SealedQueryPathBenchmark` | sealed query path: memory, hybrid, disk |
| `QueryHeavinessEstimatorBenchmark` | AQE admission estimate |
| `AdaptiveParallelScanBenchmark` | serial vs adaptive parallel scan |
| `GridBitmapIndexBenchmark` | BITMAP searchEq / searchIn / AND (wire keys) |
| `ShardPartitionMapReduceBenchmark` | MapReduce over shard partitions |
| `WireResidualBatchBenchmark` | batched residual EQ over wire bytes |
| `ForUpdateDistNettyPeerLockBenchmark` | Dist FOR UPDATE Netty lock + prepare/COMMIT |
| `run-jepsen-smoke.{ps1,sh}` | Compose N=3 stand; appends a row to RESULTS |

`run-jmh-latency` runs the first five sequentially; the remaining tracks start from JUnit (`AbstractBenchmark.runJmh`) or the JMH CLI.

## Where the numbers live

- Curated tables: [results](results.md).
- Load measurement files: [`SUMMARY.md`](../../../grid-server-core/benchmarks/results/SUMMARY.md).

Planning tables are never edited by hand: re-run the load on a calm host and replace the matching file.

## Jepsen

Stand: [`benchmarks/jepsen/README.md`](../../../benchmarks/jepsen/README.md). The smoke run appends to [`RESULTS.md`](../../../benchmarks/jepsen/RESULTS.md); multi-site results go to [`multidc/RESULTS.md`](../../../benchmarks/jepsen/multidc/RESULTS.md). Full edge matrix A–M: `run-jepsen-all-profiles.ps1` — stamp **2026-10-03** reported **13/13** safety PASS (`:valid? true`; A–K restamp **2026-10-02** was **11/11**). Profile **J** (swarm) is append-only by design (Elle list-append; no register) — see [`COVERAGE.md`](../../../benchmarks/jepsen/COVERAGE.md).

`:valid? true` states consistency. It is not a throughput figure and not a load floor. Under calm Multi-DC `*-nochao`, `:no-proposer` / connect fails discarded by Elle are settle/availability issues — fix the admit gate, do not weaken quorum/`fsync`.

Russian: [methodology.md](../../ru/performance/methodology.md).
