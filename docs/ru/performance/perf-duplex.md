# Duplex-кодирование значений

Duplex-кодирование (`dataLane` / `parityLane`) за один проход пишет поля значения и защиту от порчи данных. Оно применяется к значениям в карте и к полезной нагрузке журнала изменений (*OpLog*), стоит немного дороже по CPU, чем голый `logicalToArray`, и дешевле старого двухпроходного пути. Отключается настройкой, миграция данных не нужна.

## Когда включать

Включайте duplex, если значениям на пути карты и OpLog нужна защита от перевёрнутого бита, а небольшая надбавка на кодирование приемлема. Решение принимают по замеру: прогнать `DuplexCodecBenchmark` и сравнить `logicalToArray` с `duplexEncodeDomain`.

Откат — `grid.codec.duplex.enabled: false` и перезапуск с тем же `schema-epoch`; после этого значения пишутся обычным logical `byte[]`.

## Что меняется на пути записи

- **Кодирование за один проход.** `QuartetDuplexCodec.encodeLogical` раскладывает поля по обеим дорожкам прямо во время сериализации, без промежуточного `toArray`.
- **Доступ к полям через Unsafe.** `UnsafeFieldAccessor` вместо MethodHandle и лямбд из `DataClassPreProcessor`.
- **Проверка и восстановление.** Дорожка чётности комплементарна дорожке данных, поэтому `DuplexRepairMode.REBUILD_DATA_FROM_PARITY` восстанавливает значение после перевёрнутого бита.

## Настройка

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

## Как измерять

```bash
mvn -pl grid-server-core -DskipTests package
java -jar grid-server-core/target/benchmarks.jar DuplexCodecBenchmark
```

Сравнивайте `logicalToArray` и `duplexEncodeDomain` по среднему времени (µs/оп). Текущие цифры: [сводные результаты](results.md). Условия прогона: [методика](methodology.md).

## Проверка на порче данных

Тест `QuartetDuplexCodecTest` закрывает оба исхода:

- перевёрнутый бит в `dataLane` восстанавливается из `parityLane`;
- двойная порча при `repair-mode: FAIL` даёт исключение, а не тихо неверные данные.

## Версия схемы

И blob, и `ReplicationOp` несут `schemaEpoch`. При несовпадении операция не применяется, а не трактуется по другой раскладке. `SchemaEpochSupport` выпускает записи `BARRIER` и `SNAPSHOT_MARKER` с хешем раскладки.

## Рядом

- [Критический путь записи ORCHID](perf-bio-consensus.md)
- [Путь записи](../understand/write-path-staging.md)
- [Ёмкость и пороги](capacity-slo.md)

Английская версия: [perf-duplex.md](../../en/performance/perf-duplex.md).
