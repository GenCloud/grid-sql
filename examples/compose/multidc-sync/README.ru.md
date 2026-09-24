# Multi-DC SYNC_VOTERS_ACROSS_DC — RU

Образ `jamoa-grid-sql:local`. Конфиги: [`config/`](config/) (`voters: [b1]`, `learners: [b2]`).

```powershell
powershell -File ..\..\scripts\build-sql-image.ps1
docker compose --env-file env\mid.env up -d
```

Те же порты, что у ASYNC — один Multidc-стек за раз.

EN: [README.md](README.md)
