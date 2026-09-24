# 1-DC N=3 — RU

Образ `jamoa-grid-sql:local`. Конфиги: [`config/`](config/).

```powershell
powershell -File ..\..\scripts\build-sql-image.ps1
docker compose --env-file env\mid.env up -d
```

| Нода | SQL | HTTP | Repl | Конфиг |
|------|-----|------|------|--------|
| n1 | 15432 | 7777 | 5615 | `config/application-n1.yml` |
| n2 | 15433 | 7778 | 5616 | `config/application-n2.yml` |
| n3 | 15434 | 7779 | 5617 | `config/application-n3.yml` |

EN: [README.md](README.md)
