# 1-DC N=2 (primary + replica) — HA Load lab

Product image topology aligned with HA / JMeter Load lab profiles
(`grid-sql-server-starter` `application-primary.yml` + `application-replica.yml`).

## Start

```powershell
powershell -File ..\..\scripts\build-sql-image.ps1
cd examples\compose\1dc-n2
docker compose --env-file env\mid.env up -d
```

SQL writer: `grid://grid:grid@127.0.0.1:15432/public`  
SQL + readEndpoints: `grid://grid:grid@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433`  
Readiness (starter base-path `/`): http://127.0.0.1:7777/health/readiness  
(Default Spring layout would be `/actuator/health/readiness` — not this stack.)

## Knobs (vs `1dc-n3`)

| Knob | This stack | `1dc-n3` |
|------|------------|----------|
| hydrate | LAZY + working-set | FULL |
| swarm / placement-optimizer | on | on (`apply-auto-cutover: true`) |
| reconcile-interval-ms | 5000 | 2000 |
| cross-dc.batch-max-ops | 256 | N/A (cross-dc off) |
| replica-reads | enabled | enabled |

RU: [README.ru.md](README.ru.md)
