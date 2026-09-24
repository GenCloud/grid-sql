# Internal peer compare (Ignite + Geode)

**Internal lab only — not product documentation.** Do not cite these numbers in `docs/` or the root README.

Fair latency twins for SQL-first distributed memory — not Redis/etcd/Hazelcast class.

## Bring up

```powershell
cd benchmarks/oss-peers
docker compose up -d
```

| Service | Ports | Role |
|---------|-------|------|
| ignite | 10800 thin | SQL + cache (thin client peer) |
| geode-locator | 10334 | locator |
| geode-server | 40404 | region server |

## Compare

```powershell
powershell -File .\scripts\run-compare-peers.ps1
```

Calm host only. Harnesses: `IgniteThinCompareHarness` + `IgniteEmbeddedCompareHarness`. Output: `peer-matrix.en.md` / `peer-matrix.ru.md` in this folder (and ephemeral JSON under `grid-server-core/benchmarks/lab/`).