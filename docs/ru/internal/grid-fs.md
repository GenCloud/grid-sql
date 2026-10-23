# GridFs

Единый набор файловых помощников для каталога, запечатанных данных, OpLog, locus, overlay и временных файлов. Пакет: `org.genfork.grid.fs`.

Смысл в том, чтобы запись на диск везде была одинаковой: сначала во временный файл рядом, потом атомарное перемещение. Читатели видят новое содержимое только после завершения rename (**атомарная видимость**).

Это не crash-durability журнала мутаций: долговечность OpLog — `MappedAppendFile` / групповой `force` при включённом `grid.replication.op-log.fsync` — см. [долговременное хранение](../configure-and-operate/configuration/durability.md). GridFs не делает `force` OpLog.

| API | Роль |
|-----|------|
| `Platform.current()` | различает Windows и Unix-подобные системы |
| `GridFs.createDirs` / `createParentDirs` | создание каталогов |
| `GridFs.writeAtomic` (байты, строка, `AtomicWriter`) | запись через соседний `.tmp` плюс `ATOMIC_MOVE` (атомарная видимость после rename) |
| `GridFs.moveAtomic` | атомарная замена (`ATOMIC_MOVE` + `REPLACE_EXISTING`) |
| `GridFs.readAll` / `readLines` / `readString` | чтение, по умолчанию UTF-8 |
| `GridFs.writeString` / `appendString` | неатомарный текст, например дописывание DDL |
| `GridFs.mapReadOnly` / `unmap` | отображение файла в память только для чтения |

Если файловая система не умеет `ATOMIC_MOVE`, операция завершается `IOException` — как и в запечатанном GMAP. Отказ здесь предпочтительнее тихой полузаписи. Внутри нет `.block()` из Reactor.

На **Windows** `GridFs.moveAtomic` повторяет кратковременный `AccessDeniedException` на `REPLACE_EXISTING` с ограниченным backoff (AV/индексатор на non-mmap meta: `privileges.meta`, `index-ckpt`). После исчерпания бюджета — fail-closed, без non-atomic copy. Только sync FS-граница (boot / logic VT / sealed workers), не Netty EL. Для mmap sealed overwrite по-прежнему нужен `closeForOverwrite` до REPLACE (G-00001); retry его не заменяет.

Рядом: [обзор архитектуры](../understand/architecture-overview.md), [хранение GMAP](../understand/storage-sealed-gmap.md).

Английская версия: [grid-fs.md](../../en/internal/grid-fs.md).
