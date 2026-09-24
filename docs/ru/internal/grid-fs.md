# GridFs

Единый набор файловых помощников для каталога, запечатанных данных, OpLog, locus, overlay и временных файлов. Пакет: `org.genfork.grid.fs`.

Смысл в том, чтобы запись на диск везде была одинаковой: сначала во временный файл рядом, потом атомарное перемещение. Данные становятся видимыми только после подтверждения записи.

| API | Роль |
|-----|------|
| `Platform.current()` | различает Windows и Unix-подобные системы |
| `GridFs.createDirs` / `createParentDirs` | создание каталогов |
| `GridFs.writeAtomic` (байты, строка, `AtomicWriter`) | запись через соседний `.tmp` плюс `ATOMIC_MOVE` — видимость после подтверждения |
| `GridFs.moveAtomic` | атомарная замена с тем же правилом |
| `GridFs.readAll` / `readLines` / `readString` | чтение, по умолчанию UTF-8 |
| `GridFs.writeString` / `appendString` | неатомарный текст, например дописывание DDL |
| `GridFs.mapReadOnly` / `unmap` | отображение файла в память только для чтения |

Если файловая система не умеет `ATOMIC_MOVE`, операция завершается `IOException` — как и в запечатанном GMAP. Отказ здесь предпочтительнее тихой полузаписи. Внутри нет `.block()` из Reactor.

Рядом: [обзор архитектуры](../understand/architecture-overview.md), [хранение GMAP](../understand/storage-sealed-gmap.md).

Английская версия: [grid-fs.md](../../en/internal/grid-fs.md).
