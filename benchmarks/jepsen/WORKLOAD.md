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

Checker: **Elle** `list-append` (`elle.list-append/check`, no Graphviz directory — control image may lack `dot`). Generator emits txn mops `[:append k tok]` / `[:r k nil]` across **keys 2..16** (separate from register key 1), including **multi-mop** txns (`[:append k1 …][:r k2 …]`). Client runs all mops of one `:txn` inside one SQL `BEGIN`/`COMMIT` (`TxContext`).

**Consistency contract:** append is **SQL-only** (no client RMW). Proposer merges under **per-key** lock via `LogicalFieldCursor` → OpLog **`UPSERT`** final bytes. Multi-mop TX shares one dirty buffer until COMMIT. Non-proposer → `OrchidNotSynced` → `:fail`. Parser: UPDATE hot-path + template cache.

### 3. Join / shards (`--workload join`, `JEPSEN_JOIN_SHARDS=1`)

Same Elle mop shape as append (`[:append k tok]` / `[:r k nil]` / multi-mop TX), but storage is **parent+child**:

| Table | Role |
|-------|------|
| `jepsen_child(id, parent_id, number, status)` | append list body (Elle key = child id) |
| `jepsen_parent(id, name)` | `parent_id = child_id + 100` (cross-shard under `default-shards: 8`) |

Reads use `SELECT c.number, c.status, p.id … LEFT OUTER JOIN jepsen_parent p ON c.parent_id = p.id` — child without parent is `:fail` (`join-parent-missing`), never a forged empty Elle list. Engine accepts either ON qualifier order. Profile script: `run-jepsen-join.ps1`.

Honesty: `Unknown column` / `Unknown table` / `schema-error` in lein output → profile **FAIL** via `jepsen-honesty-gate.sh` even if Elle `:valid? true`. Elle `G2-item*` is also hard FAIL (product tip fence after cross-DC claim).

### 4. Swarm / cutover (`JEPSEN_SWARM=1`, `run-jepsen-swarm.ps1`)

Compose overlay `docker-compose.swarm.yml` enables `grid.replication.swarm` + `apply-auto-cutover`. Workload remains **append** multi-key TX. Nemesis adds `:swarm-bounce` (kill/start a follower) on top of partition/kill-proposer.

SQL listen ports (Compose): n1 `15432`, n2 `15433`, n3 `15434` (`JEPSEN_SQL_PORTS`).

**Latency gate (algorithm):** `--no-nemesis` / `run-jepsen-nochao.*` -> `qg-gate.sh` (hard p50+p95 vs Ref B*1.05; soft p99*1.15; n>=200). Prefer median of 3x 60s runs. Chaos p50/p95/p99 is contour only.

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


## FAQ: `[:r k nil]` in logs

Elle mop `[:r k nil]` on `:invoke` is a **read placeholder**, not a NULL row or consensus bug.
See [COVERAGE.md](COVERAGE.md). Full Multi-DC CI runs use **nemesis ON** for `*-chaos` cells (`MULTIDC_NEMESIS=1`); `*-nochao` is topology-only.