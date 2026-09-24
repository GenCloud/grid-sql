# ORCHID durable write critical path

A durable write costs one ORCHID agreement plus one OpLog `fsync`. Below are the stages that make up that latency, the metric behind each stage, and the work already moved off the hot path. Mixed-load throughput is a different question — see [capacity](capacity-slo.md).

## Write steps

1. Duplex-encode the value, if the codec is on — see [duplex encoding](perf-duplex.md).
2. Submit a digest to ORCHID: round trip plus quorum gather, admitted through a single in-flight propose.
3. Append to OpLog; with `fsync` on, a group force to disk.
4. Confirm `orchid/state.bin` (`confirmPersisted`).
5. Update the locus map; the HomologousRepair helper-state rewrite is **asynchronous**.
6. Advance the global sequence and ship over Netty in-DC and cross-DC, also **asynchronously**.

Steps 1–4 are synchronous, and that is the price of durability. Steps 5 and 6 sit off the hot path.

## Stage histograms

`ReplicationMetrics` collects the two stages that dominate the wait:

| Metric | Source |
|--------|--------|
| `orchidWaitP50Ns` / `orchidWaitP99Ns` | `MutationRecorder` around `appendAndWaitCommit` |
| `oplogFsyncP50Ns` / `oplogFsyncP99Ns` | group `force` in `MappedAppendFile` when `fsync=true` |

Both are exported through Actuator health and Micrometer — see [monitoring](../configure-and-operate/monitoring.md).

## Reading a p99 jump

| Stage | If p99 rises | Where to look |
|-------|--------------|---------------|
| ORCHID wait | Peer network, `R` threshold, digest load | `orchidWaitP99Ns`, the `peers` list |
| OpLog fsync | Disk, segment size, `force` contention | `oplogFsyncP99Ns`, `op-log.fsync` |
| Network ship | In-DC or cross-DC send, off the commit path | RPO, `/replication/compare` |

Rising ORCHID wait with a stable fsync points at the peer network, the `R` threshold or digest load. Rising fsync with calm ORCHID points at the disk, the segment size or `force` contention. If both rise together the host itself is overloaded, and nothing measured there should be compared against thresholds.

## How to measure

Harness: `AbstractLatencyBenchmark` driven by `scripts/run-jmh-latency.{ps1,sh}`, calm host only, no load running in parallel. Numbers: [results](results.md). Run conditions: [methodology](methodology.md).

## What has already been shortened

- Group OpLog `fsync`: concurrent waiters share one `force` call.
- Single in-flight ORCHID propose: contiguous `prevOpSeq`, no extra phase fan-out on a solo node.
- Asynchronous locus rewrite, outside the `MutationRecorder` hot path.
- In-DC ship flush on a separate executor, so it never blocks the Netty write path.

The safety invariants are unchanged: no solo commit in a configured cluster, the configured quorum still applies, and data reaches disk before it is broadcast.

## Names in API and in documentation

Biological metaphors stay in the documentation — [bio-inspired](../understand/bio-inspired.md), [placement and swarm](../understand/overlay-and-swarm.md). API and YAML use technical names: `orchid`, `HomologousRepair`, `swarm`, `overlay`.

## Related

- [Write path](../understand/write-path-staging.md)
- [ORCHID consensus](../understand/orchid-consensus.md)
- [Capacity and thresholds](capacity-slo.md)

Russian: [perf-bio-consensus.md](../../ru/performance/perf-bio-consensus.md).
