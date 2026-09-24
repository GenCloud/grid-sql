# Duplex value encoding

Duplex encoding (`dataLane` / `parityLane`) writes value fields and their corruption protection in a single pass. It applies to map values and OpLog payloads, costs slightly more CPU than a bare `logicalToArray`, and is cheaper than the older two-pass path. Switching it off is a config flag, not a data migration.

## When to enable

Enable duplex when values on the map and OpLog path need bit-flip protection and a small encode cost is acceptable. Measure before you decide: run `DuplexCodecBenchmark` and compare `logicalToArray` with `duplexEncodeDomain`.

To roll back, set `grid.codec.duplex.enabled: false` and restart with the same `schema-epoch`; values are then written as ordinary logical `byte[]`.

## What changes on the write path

- **Single-pass encode.** `QuartetDuplexCodec.encodeLogical` lays fields onto both lanes during serialization, with no intermediate `toArray`.
- **Field access through Unsafe.** `UnsafeFieldAccessor` replaces the MethodHandle and lambda accessors from `DataClassPreProcessor`.
- **Verify and repair.** The parity lane complements the data lane, so `DuplexRepairMode.REBUILD_DATA_FROM_PARITY` restores a value after a flipped bit.

## Configuration

```yaml
grid:
  codec:
    duplex:
      enabled: true
      packing: QUARTET
      verify-on-write: true
      verify-on-read: true
      repair-mode: REBUILD_DATA_FROM_PARITY
      schema-epoch: 1
      apply-to:
        map-values: true
        replication-log: true
```

## How to measure

```bash
mvn -pl grid-server-core -DskipTests package
java -jar grid-server-core/target/benchmarks.jar DuplexCodecBenchmark
```

Compare `logicalToArray` and `duplexEncodeDomain` in average time (µs/op). Current numbers: [results](results.md). Run conditions: [methodology](methodology.md).

## Corruption checks

`QuartetDuplexCodecTest` covers both outcomes:

- a flipped bit in `dataLane` is rebuilt from `parityLane`;
- double corruption with `repair-mode: FAIL` raises an exception instead of returning silently wrong data.

## Schema epoch

Both the blob and `ReplicationOp` carry `schemaEpoch`. A mismatch refuses the apply rather than reinterpreting the layout. `SchemaEpochSupport` emits `BARRIER` and `SNAPSHOT_MARKER` records with a layout hash.

## Related

- [ORCHID write critical path](perf-bio-consensus.md)
- [Write path](../understand/write-path-staging.md)
- [Capacity and thresholds](capacity-slo.md)

Russian: [perf-duplex.md](../../ru/performance/perf-duplex.md).
