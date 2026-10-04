# Grid SQL

![Grid](docs/assets/brand/grid-wordmark.svg)

[![CI](https://github.com/GenCloud/grid-sql/actions/workflows/ci.yml/badge.svg)](https://github.com/GenCloud/grid-sql/actions/workflows/ci.yml)
[![Jepsen QG](https://github.com/GenCloud/grid-sql/actions/workflows/jepsen-qg.yml/badge.svg)](https://github.com/GenCloud/grid-sql/actions/workflows/jepsen-qg.yml)

**Need SQL speed in Java without a separate cache layer that drifts from the journal?** Grid is a SQL-first in-memory DBMS with durable OpLog + sealed GridMap storage and ORCHID replication — one protocol for apps: `grid://` (reactive) or `jdbc:grid://` (JDBC).

**5 minutes:** [English quick start](docs/en/getting-started/quick-start.md) · [Русский быстрый старт](docs/ru/getting-started/quick-start.md) · [README (RU)](README.ru.md)

Licensed under the Apache License, Version 2.0 — see `LICENSE` and `NOTICE`.

## Quick start

Requires Java **25** (`--enable-preview`) and Maven **3.9+**.

```bash
mvn -pl grid-sql-server-starter -am package -DskipTests
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=capacity
```

The `capacity` profile listens on SQL port **15432** with durability on and replication off (**open auth** — no users yet).

### Hello (JDBC)

```java
Class.forName("org.genfork.grid.jdbc.GridDriver");
try (java.sql.Connection c = java.sql.DriverManager.getConnection(
        "jdbc:grid://127.0.0.1:15432/public")) {
    c.createStatement().execute(
            "CREATE TABLE IF NOT EXISTS demo (id BIGINT PRIMARY KEY, name VARCHAR)");
    c.createStatement().execute("UPSERT INTO demo (id, name) VALUES (1, 'hello')");
}
```

### Hello (reactive)

```java
org.genfork.grid.sql.client.RemoteConnectionFactory factory =
        new org.genfork.grid.sql.client.RemoteConnectionFactory(
                "127.0.0.1", 15432, "", "", 8);
factory.obtain()
        .flatMap(conn -> conn.createStatement(
                "UPSERT INTO demo (id, name) VALUES (1, 'hello')").executeUpdate())
        .block(); // demo only — not in library API
```

URLs: `grid://127.0.0.1:15432/public` · `jdbc:grid://127.0.0.1:15432/public`.  
If AUTH is enabled, use `user:pass@` in the authority (for example `grid://grid:grid@…`).

## Proofs (lab host)

| Track | Living figure |
|-------|----------------|
| HA WRITE_ONLY | ≈**4676**/s (regression floor ≈**4442**/s) |
| Consistency | Jepsen matrix — [`COVERAGE.md`](benchmarks/jepsen/COVERAGE.md) |

Details: [capacity SLO](docs/en/performance/capacity-slo.md), [results](docs/en/performance/results.md), [methodology](docs/en/performance/methodology.md).

## Learn more

| Topic | Doc |
|-------|-----|
| Spring Boot | [spring-boot](docs/en/develop/spring-boot.md) |
| ORCHID replication | [orchid-consensus](docs/en/understand/orchid-consensus.md) |
| Writer hand-off | [ha-promote](docs/en/configure-and-operate/operations/ha-promote.md) |
| Go-live checklist | [production-checklist](docs/en/getting-started/production-checklist.md) |
| Docs hub | [docs/README.md](docs/README.md) · [RU hub](docs/ru/README-ru.md) |

## Requirements

- Java **25** with `--enable-preview` (build and runtime)
- Maven 3.9+

## CI

Two GitHub Actions workflows (never co-run lab load with unit CI on the same host):

### Unit CI — [`.github/workflows/ci.yml`](.github/workflows/ci.yml)

On `push` / `pull_request` to `master`/`main`:

1. **Build and test** — reactor `mvn package` then `mvn verify` (Surefire unit + IT)
2. **Package starters** — fat jars for `grid-sql-server-starter` and `grid-sql-jepsen-starter` (uploaded as artifacts)

JMeter **load** SLO runs still need a local Apache JMeter install on a calm host; they are not part of this workflow.

### Jepsen QG — [`.github/workflows/jepsen-qg.yml`](.github/workflows/jepsen-qg.yml)

Matrix **A–M** (1-DC chaos/nochao/unclean/swarm/join, Multi-DC async/sync chaos+nochao, unclean, async-swarm, async-join, witness) — see [`COVERAGE.md`](benchmarks/jepsen/COVERAGE.md). Runs on `push` to `main`/`master`, nightly, and `workflow_dispatch` (`profiles=` filter optional). On pull requests the full Docker matrix runs only with label `jepsen`; every PR still runs the lightweight A–M sync validator in unit CI.

On GitHub Actions, `qg-gate` Ref B **p95** is **CI_ADVISORY** (p50 still hard); living Ref B / algorithm PASS claims need a calm-host re-stamp. Details: [benchmarks/jepsen/README.md](benchmarks/jepsen/README.md) (§ GitHub Actions).

**Still calm-host only** (not in Actions; run one at a time): JMH compare tracks, Load/JMeter SLO, OSS `run-compare-all`.

## Minimal config

```yaml
grid:
  durability:
    enabled: true          # local ORCHID + OpLog + sealed (solo OK)
    hydrate-mode: LAZY
  replication:
    enabled: false         # peer Netty / quorum when true
  sql-server:
    enabled: true
    port: 15432
```

Full knobs: [durability](docs/en/configure-and-operate/configuration/durability.md), [SQL server](docs/en/configure-and-operate/configuration/sql-server.md).

## How apps connect

| Client | URL (capacity / open auth) | Use |
|--------|----------------------------|-----|
| Reactive | `grid://127.0.0.1:15432/public` | Reactor / non-blocking |
| JDBC | `jdbc:grid://127.0.0.1:15432/public` | DataSource / DAO / IDEs |

With AUTH enabled, put `user:pass@` in the authority. Schema is SQL DDL (`CREATE TABLE`). One TCP multiplexes many logical sessions: client `maxTxContexts` soft default **256**, server channel hard-cap **8** (Boot does not raise it from YAML). Details: [connect clients](docs/en/getting-started/connect-clients.md), [JDBC](docs/en/develop/jdbc-tooling.md).

## Modules

| Artifact | Role |
|----------|------|
| `grid-commons` | Shared types and wire helpers (JDK-only) |
| `grid-sql-client` | ConnectionFactory, remote client, JDBC client |
| `grid-server-core` | Engine, catalog, store, SQL TCP, replication, Boot auto-config |
| `grid-sql-server-starter` | Product Boot fat jar |
| `grid-sql-jepsen-starter` | Lab nodes for consistency checks |

## Documentation

- English hub: [docs/README.md](docs/README.md)
- Russian hub: [docs/ru/README-ru.md](docs/ru/README-ru.md)

Day-2 operations: [failures](docs/en/configure-and-operate/operations/failures.md), [security](docs/en/configure-and-operate/operations/security.md), [upgrade](docs/en/configure-and-operate/operations/upgrade.md), [promote](docs/en/configure-and-operate/operations/ha-promote.md), [monitoring](docs/en/configure-and-operate/monitoring.md).

## Storage (brief)

- **Per-node** `dataDir` — not a shared cluster volume
- Durable truth: OpLog + sealed `.gmap` / `.sbpt` / `.sbm`
- RAM map and indexes are a working set; misses reload from sealed

## Boundaries

Product clients speak Grid frames over TCP: `grid://` and `jdbc:grid://` (same protocol). No in-process embed factory. Do not put Hikari-style N-socket pools in front of multiplex sessions.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CLA.md](CLA.md) (required for external PRs), and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
