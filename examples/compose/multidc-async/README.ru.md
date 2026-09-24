# Multi-DC ASYNC_SHIP — RU

Образ `jamoa-grid-sql:local`. Конфиги: [`config/`](config/) (`cross-dc.mode: ASYNC_SHIP`).

```powershell
powershell -File ..\..\scripts\build-sql-image.ps1
docker compose --env-file env\mid.env up -d
```

DC-A (ACTIVE writers): a1–a3 · DC-B (HOLD learners): b1–b2.

EN: [README.md](README.md)
