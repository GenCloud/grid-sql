# Внутреннее сравнение с peers (Ignite + Geode)

**Только внутренняя лаборатория — не продуктовая документация.** Эти цифры не цитируют в `docs/` и в корневом README.

Честные пары по задержкам для SQL-first in-memory класса — не Redis/etcd/Hazelcast.

## Подъём

```powershell
cd benchmarks/oss-peers
docker compose up -d
```

| Сервис | Порты | Роль |
|--------|-------|------|
| ignite | 10800 thin | SQL + cache (thin-client) |
| geode-locator | 10334 | locator |
| geode-server | 40404 | region server |

## Сравнение

```powershell
powershell -File .\scripts\run-compare-peers.ps1
```

Только спокойный хост. Harness: `IgniteThinCompareHarness` + `IgniteEmbeddedCompareHarness`. Результат: `peer-matrix.en.md` / `peer-matrix.ru.md` в этой папке (эфемерный JSON — в `grid-server-core/benchmarks/lab/`).