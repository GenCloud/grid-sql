# Jepsen workloads (ORCHID / grid-sql)

## Models

### 1. Register (default)

Single-key (or small key set) read/write register.

| Op | Transport | Meaning |
|----|-----------|---------|
| `read` | SQL `SELECT` via `grid://` (`JepsenSqlClient`) | Value or `:nil` |
| `write` | SQL `INSERT` via sticky proposer | Assign |

Sticky proposer discovery uses wire `ServerMeta.writerEligible` after AUTH (never HTTP status). Docker healthchecks use Actuator `/health/liveness`. Register/append/txn **ops** use `RemoteConnectionFactory`.

Checker: **linearizable** register — Jepsen `linearizable` / **Knossos** `cas-register`.

ORCHID note: only the phase-ranked proposer is writer-eligible. Clients should retry writes that fail with sync/eligibility errors (fail-closed), not treat them as successful assigns.

### 2. Append (list-append style)

| Op | Transport | Meaning |
|----|-----------|---------|
| `append` | SQL `UPDATE … number \|\| …` via `grid://` | Space-separated token append |
| `read` | SQL `SELECT` | Full string |

Checker: **Elle** `list-append` (`elle.list-append/check`, no Graphviz directory — control image may lack `dot`). Generator emits txn mops `[:append k tok]` / `[:r k nil]` across **keys 2..16** (separate from register key 1).

**Consistency contract:** append is **SQL-only** (no client RMW). Proposer merges under **per-key** lock via `LogicalFieldCursor` → OpLog **`UPSERT`** final bytes. Non-proposer → `OrchidNotSynced` → `:fail`. Parser: UPDATE hot-path + template cache.

SQL listen ports (Compose): n1 `15432`, n2 `15433`, n3 `15434` (`JEPSEN_SQL_PORTS`).

**Latency gate (algorithm):** `--no-nemesis` / `run-jepsen-nochao.*` -> `qg-gate.ps1` (hard p50+p95 vs Ref B*1.05; soft p99*1.15; n>=200). Prefer median of 3x 60s runs. Chaos p50/p95/p99 is contour only.

Latency contour for the living consistency stamp: see [RESULTS.md](RESULTS.md).

## Nemesis

| Fault | Script / Jepsen | Expectation |
|-------|-----------------|-------------|
| Network partition (1 vs 2) | `nemesis-partition.sh isolate n3` | Minority refuses writes; majority may continue if quorum holds |
| Heal | `nemesis-partition.sh heal` | Catch-up / HomologousRepair; append history converges |
| Kill proposer | `nemesis-kill-proposer.sh` | New proposer; in-flight writes fail-closed or retry OK |

## Generators (Clojure)

See `clojure/src/jamoa_jepsen/core.clj`:

- Mix of reads + writes (or appends)
- Nemesis schedule: partition ↔ heal, occasional `kill-proposer`
- Time box: 60–180s for CI; longer for nightly

## Scope v1 (1-DC)

- N=3 same DC (`dc-a`), digest quorum MAJORITY
- Multi-DC latency Jepsen is **after** F1 voters

## Pass criteria

1. No linearizability violations on successful ops
2. Failed ops (timeout / `OrchidNotSynced` / not eligible) appear as crashes/fails in history, never as successful divergent writes
3. After heal, append histories on all nodes agree for keys touched
4. Stamp `RESULTS.md` with date, commit, pass/fail, command


## Client URL

grid://…?maxConnections=1&maxTxContexts=64 — soft session cap (concurrent autocommit needs headroom above worker count).
