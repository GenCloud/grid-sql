# Grid SQL

![Grid](docs/assets/brand/grid-wordmark.svg)

[![CI](https://github.com/GenCloud/grid-sql/actions/workflows/ci.yml/badge.svg)](https://github.com/GenCloud/grid-sql/actions/workflows/ci.yml)

SQL-first in-memory DBMS with durable local storage (OpLog + sealed GridMap) and ORCHID replication. Applications connect over TCP with `grid://` (reactive) or `jdbc:grid://` (stable sync peer).

Licensed under the Apache License, Version 2.0 — see `LICENSE` and `NOTICE`.

## Quick start

```bash
mvn -pl grid-sql-server-starter -am package -DskipTests
java -jar grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar --spring.profiles.active=capacity
```

Connect (SQL port **15432**):

```
grid://grid:grid@127.0.0.1:15432/public
```

Step-by-step: [English quick start](docs/en/getting-started/quick-start.md) · [Русский быстрый старт](docs/ru/getting-started/quick-start.md).

## Requirements

- Java **25** with `--enable-preview` (build and runtime)
- Maven 3.9+

## CI

GitHub Actions (`.github/workflows/ci.yml`) on `push` / `pull_request` to `master`/`main`:

1. **Build and test** — `mvn verify` for the reactor except `grid-sql-jmeter` (needs a local Apache JMeter install)
2. **Package starters** — fat jars for `grid-sql-server-starter` and `grid-sql-jepsen-starter` (uploaded as artifacts)

Lab capacity gates (QG / Jepsen / Multi-DC / JMH / Load) are **not** in CI — run them separately on a calm host.

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

| Client | URL | Use |
|--------|-----|-----|
| Reactive | `grid://user:pass@host:15432/public` | Reactor / non-blocking |
| JDBC | `jdbc:grid://user:pass@host:15432/public` | DataSource / DAO / IDEs |

Schema is SQL DDL (`CREATE TABLE`). One TCP can multiplex many logical sessions (`maxTxContexts`). Details: [connect clients](docs/en/getting-started/connect-clients.md), [JDBC](docs/en/develop/jdbc-tooling.md).

## Modules

| Artifact | Role |
|----------|------|
| `grid-commons` | Shared types and wire helpers (JDK-only) |
| `grid-sql-client` | ConnectionFactory, remote client, JDBC tooling |
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
