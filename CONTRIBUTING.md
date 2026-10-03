# Contributing to Grid SQL

Thanks for helping. This document covers how to build, test, and propose changes. Behavior expectations: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Prerequisites

- JDK **25** with `--enable-preview`
- Maven **3.9+**
- Git

Save all text sources as **UTF-8 without BOM** (not UTF-16). On Windows, avoid editors or redirects that write UTF-16 by default.

## Build and test

```bash
mvn -pl grid-sql-server-starter -am package -DskipTests
mvn -pl grid-server-core,grid-sql-client -am test
```

Full reactor verify (when you touch shared modules):

```bash
mvn verify
```

Do not co-run lab load (JMeter / Jepsen / JMH) with unit CI on the same host. Performance floors and gate order: [docs/en/performance/methodology.md](docs/en/performance/methodology.md).

## Engineering rules (short)

- SQL parse = ANTLR only (`SimplifiedSql.g4`) — no hand-rolled keyword parsers in the engine.
- No `.block()` in library API; no `CompletableFuture.*Async` without an explicit executor; no `ForkJoinPool.commonPool()` / `parallelStream()` on product paths.
- Hot query / join / index paths stay on wire bytes (`byte[]` / `WireSpan`) — no mid-pipeline decode to `Object`.
- Explicit imports; no `import pkg.*`; no `var` in Java under `org.genfork.grid.*`.
- Do not weaken durability, quorum, `fsync`, or living capacity floors to green a stamp.

More detail: [docs/en/internal/development.md](docs/en/internal/development.md).

## Pull requests

1. Open an issue for non-trivial changes when practical.
2. Keep diffs focused; update EN **and** RU docs when you change user-facing behavior.
3. Describe *why* in the PR body; link issues.
4. Ensure `mvn test` (touched modules) passes locally.

## Documentation

- Hubs: [docs/README.md](docs/README.md) · [docs/ru/README-ru.md](docs/ru/README-ru.md)
- First-run: [quick start EN](docs/en/getting-started/quick-start.md) · [RU](docs/ru/getting-started/quick-start.md)
- Match the voice of nearby pages (short scene, mechanics, tables, end links). Prefer editing existing guides over adding parallel style guides.

## Where to ask

- Bugs and features: GitHub Issues on this repository
- Security: follow the project’s disclosed channel in `SECURITY.md` if present; otherwise open a private report with the maintainers

## License

By contributing you agree that your contributions are licensed under the Apache License, Version 2.0 (`LICENSE`).
