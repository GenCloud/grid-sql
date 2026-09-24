# 1-DC N=2 (primary + replica) — лаб HA Load

Топология product-образа, совпадающая с живыми HA / JMeter Load гейтами
(`application-primary.yml` + `application-replica.yml` в `grid-sql-server-starter`).

```powershell
cd examples\compose\1dc-n2
docker compose --env-file env\mid.env up -d
```

Writer: `grid://grid:grid@127.0.0.1:15432/public`  
С репликой: `?readEndpoints=127.0.0.1:15433`

EN: [README.md](README.md)