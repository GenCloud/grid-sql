# 1-DC N=2 (primary + replica) — лаб HA Load

Топология product-образа, выровненная с лабораторными профилями HA / JMeter Load
(`application-primary.yml` + `application-replica.yml` в `grid-sql-server-starter`).

```powershell
powershell -File ..\..\scripts\build-sql-image.ps1
cd examples\compose\1dc-n2
docker compose --env-file env\mid.env up -d
```

Writer: `grid://grid:grid@127.0.0.1:15432/public`  
С репликой: `?readEndpoints=127.0.0.1:15433`  
Readiness (starter, base-path `/`): http://127.0.0.1:7777/health/readiness

## Рычаги (против `1dc-n3`)

| Рычаг | Этот стек | `1dc-n3` |
|-------|-----------|----------|
| hydrate | LAZY + working-set | FULL |
| swarm / placement-optimizer | вкл. | вкл. (`apply-auto-cutover: true`) |
| reconcile-interval-ms | 5000 | 2000 |
| replica-reads | вкл. | вкл. |

EN: [README.md](README.md)
