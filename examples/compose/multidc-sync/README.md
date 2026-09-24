# Multi-DC SYNC_VOTERS_ACROSS_DC — EN

Product image `jamoa-grid-sql:local`. Configs: [`config/`](config/) (`voters: [b1]`, `learners: [b2]`).

```powershell
powershell -File ..\..\scripts\build-sql-image.ps1
docker compose --env-file env\mid.env up -d
```

Same host ports as ASYNC — only one Multidc lab at a time.

RU: [README.ru.md](README.ru.md)


## Witness

Service **w1** (ports 15437 / 7782 / 5620) is included for Hold+Hold+Witness claim quorum, matching Jepsen Multidc peer lists. Mode: SYNC_VOTERS_ACROSS_DC.
