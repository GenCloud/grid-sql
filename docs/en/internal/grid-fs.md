# GridFs

Filesystem helpers shared by catalog, sealed files, OpLog, locus, overlay and temporary I/O, in package `org.genfork.grid.fs`. The point is that every disk write behaves the same way: write a sibling temporary file, then move it atomically, so data becomes visible only after it is confirmed.

| API | Role |
|-----|------|
| `Platform.current()` | Windows versus Unix-like |
| `GridFs.createDirs` / `createParentDirs` | Ensure directories exist |
| `GridFs.writeAtomic` (bytes, String, `AtomicWriter`) | Sibling `.tmp` plus `ATOMIC_MOVE` |
| `GridFs.moveAtomic` | Atomic replace under the same rule |
| `GridFs.readAll` / `readLines` / `readString` | Reads, UTF-8 by default |
| `GridFs.writeString` / `appendString` | Non-atomic text, for example appending DDL |
| `GridFs.mapReadOnly` / `unmap` | Read-only memory mapping |

If the filesystem does not support `ATOMIC_MOVE`, writers throw `IOException` — the same stance as sealed GMAP, because a refusal is better than a silent half-write. No Reactor `.block()` inside.

## Related

- [Architecture overview](../understand/architecture-overview.md)
- [Sealed GMAP storage](../understand/storage-sealed-gmap.md)

Russian: [grid-fs.md](../../ru/internal/grid-fs.md).
