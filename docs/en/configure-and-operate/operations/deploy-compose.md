# Compose deploy (internal HA)

Lab docker-compose topologies for fault-tolerant Grid SQL. This is a **debug / reference lab stand**, not a production blueprint: ports, quorum, and images are tuned for one host. Tree: [`examples/compose/`](../../../../examples/compose/).

On a laptop, quorum and RTT do not look like production: cite compose numbers as lab results, not as a claimed cluster maximum. For production topology see [HA](cluster-ha-highload.md) and [multi-site](multi-dc.md).

## Working image (not Jepsen)

Compose stands use **`jamoa-grid-sql:local`** built from module **`grid-sql-server-starter`**.

Do **not** use `jamoa-grid-jepsen:local` here — that image is for chaos / Elle / Knossos only (`benchmarks/jepsen/`).

```powershell
powershell -File .\examples\scripts\build-sql-image.ps1
# Fat jar: grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar
# Dockerfile: examples/docker/Dockerfile
```

Override: `GRID_IMAGE=my-registry/grid-sql:tag` in the env file or shell.

## Topology-matched knobs

YAML under each topology matches the reference Jepsen / HA load / multi-DC stands:

| Knob | Value | Source |
|------|-------|--------|
| orchid order-threshold / digest-quorum | `0.85` / `MAJORITY` | Jepsen + primary |
| orchid max-propose-in-flight | `64` | HA Load primary/replica |
| durability + op-log fsync | on | product + Multidc Jepsen |
| health validate-group-membership | `false` | Jepsen + primary |
| 1dc-n2 hydrate | LAZY | HA Load primary/replica |
| all HA labs swarm / placement / autocutover | on / on / true | HA load stands |
| 1dc-n3 / Multidc swarm | on + apply-auto-cutover | HA load / multi-site stands |
| Multidc witness w1 | ports 15437 / 7782 / 5620 | Jepsen Multidc peers |

## Node configs (bind-mounted)

Each topology ships **real** Spring YAML under `config/`, mounted to `/app/config`:

| Topology | Files |
|----------|-------|
| `1dc-n2` | `config/application-primary.yml`, `application-replica.yml` |
| `1dc-n3` | `config/application-n1.yml`, `n2`, `n3` |
| `multidc-async` | `config/application-a1.yml` … `b2.yml`, `w1.yml` (`ASYNC_SHIP`) |
| `multidc-sync` | same names (`SYNC_VOTERS_ACROSS_DC`, voters `[b1]`) + `w1.yml` |

Compose references them explicitly:

```yaml
command:
  - --spring.config.additional-location=file:/app/config/application-n2.yml
volumes:
  - ./config:/app/config:ro
```

Edit peers / ports / `cluster-id` in those files — they define the lab.

## Topologies

| Compose | Topology | Apply when | Diagram |
|---------|----------|------------|---------|
| `1dc-n2` | N=2 primary + replica | JMeter Load / WRITE·READ | [start a cluster](../../getting-started/start-cluster.md) |
| `1dc-n3` | N=3 same DC | Everyday HA, writer hand-off | [HA in one DC](cluster-ha-highload.md) |
| `multidc-async` | 3+2+w1 ASYNC_SHIP | Cross-DC RPO / ship lag | [Multi-DC ASYNC](cluster-multidc-highload.md) |
| `multidc-sync` | 3+2+w1 SYNC_VOTERS | Cross-DC commit latency cost | [Multi-DC SYNC](cluster-multidc-highload.md) |

Client pin and Witness (quorum for role capture; does not serve app read/write): [promote a node](ha-promote.md).

Load tiers `low` / `mid` / `high` only change JVM heap via `env/*.env`.

```powershell
cd examples\compose\1dc-n2
docker compose --env-file env\mid.env up -d
# Writer: grid://grid:grid@127.0.0.1:15432/public
# Reads:  grid://grid:grid@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433

cd examples\compose\1dc-n3
docker compose --env-file env\high.env up -d
# SQL: grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434/public
# Readiness (starter): http://127.0.0.1:7777/health/readiness
```

ASYNC and SYNC Multidc share host ports — one stack at a time; do not overlap Jepsen Multidc.

## OpLog restart (no purge)

Kill mid-journal-append → zero `length` at EOF. The server **truncates torn tail** and boots with intact records.

Graceful SIGTERM / Spring destroy / JVM shutdown hook: force mmap + atomic `.wpos`. **SIGKILL / SIGSEGV** never run hooks — next open uses torn-tail replay.

`ORCHID reject non-contiguous commit expected=N got=M` under chaos is **expected** (peer behind → HomologousRepair).

## Consistency checks (separate from compose)

Do not co-run consistency checks, internal JMH, and JMeter on one machine. Order: [methodology](../../performance/methodology.md).

```powershell
powershell -File .\benchmarks\jepsen\scripts\run-jepsen.ps1 -Fast -TimeLimit 30
# Internal JMH tracks — separately, not with load
```