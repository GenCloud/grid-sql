# ORCHID: TLA+ и TLC

Формальная модель протокола ORCHID. Она нужна, чтобы гонки за роль писателя и за коммит проверялись не только тестами, но и исчерпывающим обходом состояний.

## Спецификации и конфигурации

| Спецификация | Конфигурация | Что проверяет |
|--------------|--------------|---------------|
| [`spec/orchid/OrchidLog.tla`](../../spec/orchid/OrchidLog.tla) | [`OrchidLog.cfg`](../../spec/orchid/OrchidLog.cfg) / [`OrchidLog-fast.cfg`](../../spec/orchid/OrchidLog-fast.cfg) | **Жёсткий гейт** для одного ЦОД: `Nodes={n1,n2,n3}`, `MaxSeq=3` |
| та же | [`OrchidLog-heavy.cfg`](../../spec/orchid/OrchidLog-heavy.cfg) | **По ночам, по желанию:** `MaxSeq=5`. Спецификация та же — разделения, отказ конкуренту, забывание и восстановление узла |
| [`spec/orchid/OrchidLogMultiDc.tla`](../../spec/orchid/OrchidLogMultiDc.tla) | [`OrchidLogMultiDc.cfg`](../../spec/orchid/OrchidLogMultiDc.cfg) | **Жёсткий гейт:** локальные голосующие плюс удалённый голосующий по digest `r1`, `MaxSeq=2` |
| [`spec/orchid/RegionClaim.tla`](../../spec/orchid/RegionClaim.tla) | [`RegionClaim.cfg`](../../spec/orchid/RegionClaim.cfg) | **Жёсткий гейт**: инвариант `AtMostOneActive`; лог в `tlc-out/last-run-region-claim.log` |

## Модель для одного ЦОД

- Состав голосующих фиксирован конфигурацией: забывание узла **не** сжимает кворум.
- Предлагающий один, он выбран по фазе; конкурирующее предложение не коммитит (`NackCompetitor`).
- `prevOpSeq` непрерывен, инвариант `NoFork` держится на digest слота.
- Меньшинство при разделении сети не коммитит; большинство и последующее восстановление тоже смоделированы.
- У последователей `persisted` наступает до продвижения `lastCommitted`.

## Модель для нескольких ЦОД

В режиме `SYNC_VOTERS_ACROSS_DC` доказано:

```
Commit => LocalQuorum(localReachable) /\ RemoteDigestsMet
```

- Удалённые голосующие подтверждают digest по отдельному WAN-каналу (`RemoteAck`) и **не** входят в `localReachable` (при `phaseCoupling=false`, инвариант `RemoteNotInLocalR`).
- Действие `PartitionRemote` опустошает `remoteReachable`, и коммит блокируется даже при локальном большинстве. Это и есть отказ вместо тихой потери данных.
- Это **не** фазовая синхронизация (модель Курамото), растянутая через WAN.

## Запуск

```bash
./scripts/run-tlc-orchid.sh
pwsh ./scripts/run-tlc-orchid.ps1
# тяжёлый вариант отдельно, жёстким гейтом CI не является:
./scripts/run-tlc-orchid.sh --heavy
pwsh ./scripts/run-tlc-orchid.ps1 -Heavy
```

Жёсткий гейт — это быстрый прогон одного ЦОД плюс Multi-DC плюс RegionClaim. При нарушении инварианта скрипт завершается с ненулевым кодом. Задача CI: `tlc-orchid`.

## Последний успешный прогон (локально)

- Отметки: `2026-09-18-residuals-r0`, волна AQE `2026-09-18-aqe-residuals`, до них `2026-09-17-stabilize`.
- Быстрый `MaxSeq=3`: **1704** различных состояния — пройдено.
- Multi-DC: **408** различных состояний — пройдено.
- Тяжёлый `MaxSeq=5`: **24060** различных состояний, около 119573 сгенерированных — пройдено (ночной прогон).

## Границы обхода

| Спецификация | Различных состояний | Сгенерировано |
|--------------|--------------------:|--------------:|
| OrchidLog, быстрый (`MaxSeq=3`) | **1704** | ~8441 |
| OrchidLogMultiDc | **408** | ~2299 |
| OrchidLog, тяжёлый (`MaxSeq=5`) | **24060** | ~119573 |

Рядом: [ORCHID](../understand/orchid-consensus.md), [правила разработки](development.md).

Английская версия: [orchid-tla.md](../../en/internal/orchid-tla.md).
