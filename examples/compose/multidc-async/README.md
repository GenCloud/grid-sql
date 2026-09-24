# Multi-DC ASYNC_SHIP — EN

Product image `jamoa-grid-sql:local`. Configs: [`config/`](config/) (`cross-dc.mode: ASYNC_SHIP`).

```powershell
powershell -File ..\..\scripts\build-sql-image.ps1
docker compose --env-file env\mid.env up -d
```

DC-A (ACTIVE writers): a1–a3 · DC-B (HOLD learners): b1–b2.

RU: [README.ru.md](README.ru.md)


## Witness

Service **w1** (ports 15437 / 7782 / 5620) is included for Hold+Hold+Witness claim quorum, matching Jepsen Multidc peer lists. Mode: ASYNC_SHIP.
