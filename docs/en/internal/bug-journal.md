# Bug journal (`Test_G_NNNNN`)

Every Grid regression gets a number, an entry here and a test carrying the same number. The order matters: entry first, then the fix and the test.

| Field | Rule |
|-------|------|
| Ticket | `G-NNNNN`, monotonic (`00001`, `00002`, …) |
| Test | Class or method `Test_G_NNNNN` (or `@DisplayName("Test_G_NNNNN")` plus `test_G_NNNNN_*`) under `grid-server-core/src/test/java/` |
| Entry | Id, date, symptom, root cause, fix, status `OPEN` or `FIXED`, test path |
| New bug | Take the next free number |

---

## G-00001 — sealed checkpoint replace with a live mmap (Windows)

| Field | Value |
|-------|-------|
| **Status** | FIXED |
| **Date** | 2026-09-17 |
| **Test** | `index.unit.replication.Test_G_00001` |
| **Symptom** | `WARN [repl-bg] Sealed checkpoint failed: java.lang.IllegalStateException: sealed dump failed append_domain#1` during the periodic sealed dump (about every 30 s) after INSERT or DBeaver activity |
| **Root cause** | `SealedGridMapService.dumpDomain` replaced `*.gmap` via `ATOMIC_MOVE` with `REPLACE_EXISTING` while the previous `SealedGridMapReader` still held the file mapped. On Windows that raises `AccessDeniedException` or `FileSystemException`; Linux unlink semantics usually mask it, which is why the defect stayed invisible for so long |
| **Fix** | Release the live reader first — unmap and close (`releaseReader`) **before** `writeNodes` and orphan deletion — then write, open and `swapReader`. The checkpoint warning now logs the throwable, so the cause is preserved |
| **Notes** | Sealed hard gates must not be weakened. Regression check: a double dump with a live reader must succeed on both Windows and Linux |

## Related

- [Development](development.md)

Russian: [bug-journal.md](../../ru/internal/bug-journal.md).
