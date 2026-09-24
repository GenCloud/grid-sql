# Deploy compose (internal HA labs) — EN

Fault-tolerant Grid SQL clusters for local / lab use.

**Product image:** `jamoa-grid-sql:local` built from **`grid-sql-server-starter`** (not the Jepsen chaos image).

Knobs are aligned with living Jepsen / HA Load / Multidc gates (ORCHID `0.85` / `MAJORITY` / `max-propose-in-flight: 64`, durability+fsync, `validate-group-membership: false`).

RU: [README.ru.md](README.ru.md) · Docs: [docs/en/configure-and-operate/operations/deploy-compose.md](../../docs/en/configure-and-operate/operations/deploy-compose.md)

## Build image once

```powershell
powershell -File .\examples\scripts\build-sql-image.ps1
# → jamoa-grid-sql:local  (fat jar: grid-sql-server-starter-1.0-SNAPSHOT.jar)
```

## Topologies

| Path | Topology | Config source of truth | When to use |
|------|----------|------------------------|-------------|
| `1dc-n2/` | Primary + replica (N=2) | `application-primary/replica.yml` (HA Load) | JMeter Load / living WRITE·READ floors |
| `1dc-n3/` | Same-DC N=3 ORCHID | Jepsen topology + HA Load swarm/autocutover | Sticky promote, everyday HA |
| `multidc-async/` | 3+2+w1 ASYNC_SHIP | Jepsen multidc `async/` + witness | Cross-DC RPO / ship lag |
| `multidc-sync/` | 3+2+w1 SYNC_VOTERS | Jepsen multidc `sync-voters/` + witness | Cross-DC commit cost |

## Load tiers (`env/*.env`)

| Tier | Heap (guide) | Use |
|------|--------------|-----|
| **low** | 512m–1g | Laptop smoke |
| **mid** | 1–2g | Default / JMeter 8–32t |
| **high** | 2–4g | highload++ / soak |

## 1-DC N=2 (HA Load)

```powershell
cd examples\compose\1dc-n2
docker compose --env-file env\mid.env up -d
# Writer: grid://grid:grid@127.0.0.1:15432/public
# Reads:  ?readEndpoints=127.0.0.1:15433
```

LAZY hydrate, swarm + placement-optimizer on — same knobs as host `primary`/`replica` profiles.

## 1-DC N=3

```powershell
cd examples\compose\1dc-n3
docker compose --env-file env\mid.env up -d
# SQL: grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434/public
```

FULL hydrate; **swarm + placement-optimizer + `apply-auto-cutover: true`** (proven HA Load / soak); `replica-reads-enabled` on.

## Multi-DC (+ witness w1)

```powershell
cd examples\compose\multidc-async
docker compose --env-file env\high.env up -d
# Witness SQL/HTTP: 15437 / 7782 / repl 5620
```

ASYNC and SYNC share host ports — run one Multidc stack at a time. Do not overlap Jepsen Multidc (`benchmarks/jepsen/multidc`).

## OpLog / restart

Do **not** purge volumes to “fix” restart. Torn-tail OpLog recovery + graceful SIGTERM flush keep data. See docs.