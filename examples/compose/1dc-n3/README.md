# 1-DC N=3 — EN

Product image `jamoa-grid-sql:local`. Configs: [`config/`](config/).

```powershell
powershell -File ..\..\scripts\build-sql-image.ps1
docker compose --env-file env\mid.env up -d
```

| Node | SQL | HTTP | Repl | Config |
|------|-----|------|------|--------|
| n1 | 15432 | 7777 | 5615 | `config/application-n1.yml` |
| n2 | 15433 | 7778 | 5616 | `config/application-n2.yml` |
| n3 | 15434 | 7779 | 5617 | `config/application-n3.yml` |

RU: [README.ru.md](README.ru.md)


## Gate alignment

ORCHID/digest-quorum/max-propose-in-flight: 64, ha.replica-reads-enabled, alidate-group-membership: false match Jepsen + HA Load references. Swarm / placement / autocutover are **on** (HA Load proven). Use `1dc-n2` for LAZY hydrate + N=2 Load floors.
