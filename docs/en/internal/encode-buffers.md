# Encode buffers: heap or direct

All little-endian encode and decode goes through [`EncodeBuffers`](../../../grid-commons/src/main/java/org/genfork/grid/nio/EncodeBuffers.java). The heap-or-direct choice is made per call site and follows measurements, not the general assumption that direct is faster. Raw `ByteBuffer.allocate*` calls are not scattered through the code.

## Call-site policy

| API | When it applies |
|-----|-----------------|
| `allocateWireLe` / `allocateHeapLe` | Small SQL frames (`SqlWire` on `byte[]`) and replication RPC (`ReplicationRpcCodec`) — paths that always end in `toByteArray`; heap wins on small EXEC frames |
| Netty pooled buffers with `SqlWire.*Into` and [`SqlFrames`](../../../grid-commons/src/main/java/org/genfork/grid/sql/netty/SqlFrames.java) | Outbound `ROW_DESC`, `ROW_DATA`, `BATCH_EXEC` — no intermediate `byte[]` |
| `allocateDirectLe` | Off-heap LE staging that never calls `toByteArray` |
| `allocateLe(size)` | Compatibility only; controlled by `-Dgrid.encode.direct` (default **false**) |
| `wrapLe` | Decoding an inbound `byte[]` |
| Sealed files, OpLog, `index-ckpt` | mmap, native and big-endian file formats — the LE wire policy does not apply |

In the product this means: small frames on heap, and large-frame streaming on both server and client through `encodeInto` into a pooled `ByteBuf` via `SqlFrames`. Do not set `grid.encode.direct=true` globally. The dead `SqlWireNetty` facade was removed; `SqlWire` and `SqlFrames` remain.

## Already on `encodeInto`

- `SqlWire.execInto`, `utf8Into`, `batchExecInto`, `rowDataInto`, `rowDescInto`, `lengthPrefixedBytesInto`
- `SqlFrames.write` and `writeAndFlush` for the product Netty outbound path

Benchmarks kept in rotation: `DirectVsHeapEncodeIntoBenchmark`, `DirectVsHeapDiskStagingBenchmark`, `DirectVsHeapEncodeBenchmark`. Current numbers: [results](../performance/results.md).

## Override

| Source | Effect, `allocateLe(size)` only |
|--------|---------------------------------|
| `-Dgrid.encode.direct=true` | use direct |
| Env `GRID_ENCODE_DIRECT` | same, when the system property is unset |

OpLog and sealed readers stay memory-mapped next to this utility and are not governed by its policy.

## Related

- [Sealed GMAP storage](../understand/storage-sealed-gmap.md)
- [Result streaming](../develop/wire-streaming.md)
- [Results](../performance/results.md)

Russian: [encode-buffers.md](../../ru/internal/encode-buffers.md).
