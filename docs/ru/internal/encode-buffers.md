# Буферы кодирования: heap или direct

Всё кодирование и декодирование в little-endian идёт через [`EncodeBuffers`](../../../grid-commons/src/main/java/org/genfork/grid/nio/EncodeBuffers.java). Выбор между heap и direct делается **по месту вызова** и опирается на измерения, а не на общее правило «direct быстрее». Сырые `ByteBuffer.allocate*` по коду не размазываем.

## Политика по местам вызова

| API | Когда применяется |
|-----|-------------------|
| `allocateWireLe` / `allocateHeapLe` (**мелкие кадры**) | Небольшие SQL-кадры (`SqlWire` на `byte[]`) и RPC репликации (`ReplicationRpcCodec`) — те, что всегда заканчиваются `toByteArray`. Для мелкого EXEC измерения дают выигрыш у heap |
| Pooled-буферы Netty плюс `SqlWire.*Into` и [`SqlFrames`](../../../grid-commons/src/main/java/org/genfork/grid/sql/netty/SqlFrames.java) (**крупные кадры**) | Исходящие `ROW_DESC`, `ROW_DATA`, `BATCH_EXEC` — без промежуточного `byte[]` |
| `allocateDirectLe` | Off-heap подготовка данных в LE, которая никогда не вызывает `toByteArray` |
| `allocateLe(size)` | Только для совместимости; управляется `-Dgrid.encode.direct` (по умолчанию **false**) |
| `wrapLe` | Декодирование входящего `byte[]` |
| Запечатанные данные, OpLog, `index-ckpt` | Это mmap, нативные и big-endian форматы файлов — политика LE-провода к ним **не** относится |

В продукте это выглядит так: мелкие кадры на heap, а серверная и клиентская потоковая выдача крупных кадров — `encodeInto` в pooled `ByteBuf` через `SqlFrames`. Глобально включать `grid.encode.direct=true` не надо. Мёртвый фасад `SqlWireNetty` удалён — остались `SqlWire` и `SqlFrames`.

## Что уже переведено на `encodeInto`

- `SqlWire.execInto`, `utf8Into`, `batchExecInto`, `rowDataInto`, `rowDescInto`, `lengthPrefixedBytesInto`.
- `SqlFrames.write` и `writeAndFlush` для продуктового исходящего пути Netty.

Бенчмарки сохранены и продолжают гоняться: `DirectVsHeapEncodeIntoBenchmark`, `DirectVsHeapDiskStagingBenchmark`, `DirectVsHeapEncodeBenchmark`. Текущие цифры: [сводные результаты](../performance/results.md).

## Переключатель

| Источник | Влияет только на `allocateLe(size)` |
|----------|-------------------------------------|
| `-Dgrid.encode.direct=true` | использовать direct |
| Переменная окружения `GRID_ENCODE_DIRECT` | то же, если системное свойство не задано |

Читатели OpLog и запечатанных файлов остаются отображёнными в память и лежат рядом с этой утилитой, но её политикой не управляются.

Рядом: [хранение GMAP](../understand/storage-sealed-gmap.md), [потоковая выдача](../develop/wire-streaming.md), [сводные результаты](../performance/results.md).

Английская версия: [encode-buffers.md](../../en/internal/encode-buffers.md).
