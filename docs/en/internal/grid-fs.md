# GridFs

Filesystem helpers shared by catalog, sealed files, OpLog, locus, overlay and temporary I/O, in package `org.genfork.grid.fs`. The point is that every disk write behaves the same way: write a sibling temporary file, then move it atomically, so readers see the new content only after the rename completes (**atomic visibility**).

That is not crash-durability of the mutation journal: OpLog durability is `MappedAppendFile` / group `force` when `grid.replication.op-log.fsync` is on — see [durability](../configure-and-operate/configuration/durability.md). GridFs does not `force` the OpLog.

| API | Role |
|-----|------|
| `Platform.current()` | Windows versus Unix-like |
| `GridFs.createDirs` / `createParentDirs` | Ensure directories exist |
| `GridFs.writeAtomic` (bytes, String, `AtomicWriter`) | Sibling `.tmp` plus `ATOMIC_MOVE` (atomic visibility after rename) |
| `GridFs.moveAtomic` | Atomic replace (`ATOMIC_MOVE` + `REPLACE_EXISTING`) |
| `GridFs.readAll` / `readLines` / `readString` | Reads, UTF-8 by default |
| `GridFs.writeString` / `appendString` | Non-atomic text, for example appending DDL |
| `GridFs.mapReadOnly` / `unmap` | Read-only memory mapping |

If the filesystem does not support `ATOMIC_MOVE`, writers throw `IOException` — the same stance as sealed GMAP, because a refusal is better than a silent half-write. No Reactor `.block()` inside.

On **Windows**, `GridFs.moveAtomic` retries transient `AccessDeniedException` on `REPLACE_EXISTING` with a bounded backoff (AV/indexer sharing violations on non-mmap meta such as `privileges.meta` and `index-ckpt`). After the budget is exhausted the error is fail-closed — there is no non-atomic copy fallback. Call only from sync FS boundaries (boot / logic VT / sealed workers), never Netty EL. Live mmap sealed overwrite remains a separate path: callers must `closeForOverwrite` before REPLACE (G-00001); retry does not replace that unmap.

## Related

- [Architecture overview](../understand/architecture-overview.md)
- [Sealed GMAP storage](../understand/storage-sealed-gmap.md)

Russian: [grid-fs.md](../../ru/internal/grid-fs.md).
