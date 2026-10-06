# Jepsen coverage matrix (ORCHID / recovery / consistency)

Honest mapping: **invariant → fault → workload → checker → CI job → stamp**.
Do not claim PASS from scaffold-only or `--no-nemesis` when the cell is labeled chaos.

## FAQ: `[:r k nil]` in logs

Elle mop `[:r k nil]` on **`:invoke`** means "read key k"; `nil` is a **placeholder**, not a NULL row or consensus bug.
On **`:ok`** the value is the token list (e.g. `[:r 16 ["t14" ...]]`). Anomalies are reported by Elle/Knossos, not by `nil` in invoke.

## Matrix (full bash entrypoints)

| ID | `matrix.config` / entrypoint | Nemesis | Workloads | Checker | Invariants covered |
|----|------------------------------|---------|-----------|---------|-------------------|
| A | `1dc-chaos` → `scripts/run-jepsen.sh` | partition, kill-proposer, kill/revive-dc-a (1-DC skips DC-A) | register + append (multi-mop SQL TX) | Knossos + Elle | ORCHID admit, sticky writer, heal catch-up, multi-key TX |
| B | `1dc-unclean-revive` → `scripts/run-jepsen-unclean-revive.sh` | long proposer down, no purge | register + append | Knossos + Elle | unclean revive, sticky/rediscover, list monotonicity |
| C | `1dc-nochao` → `scripts/run-jepsen-nochao.sh` + `qg-gate.sh` | none | register + append | Knossos + Elle + Ref B latency | baseline consistency + p50 (p95 advisory on GHA) |
| D | `multidc-async-chaos` → `multidc/scripts/run-multidc-chaos.sh async` | DC-link partition/heal, kill-voter, kill/revive DC-A | register + append | Knossos + Elle | ASYNC_SHIP under faults |
| E | `multidc-sync-chaos` → `multidc/scripts/run-multidc-chaos.sh sync-voters` | same | register + append | Knossos + Elle | SYNC_VOTERS under faults |
| F | `multidc-async-nochao` → `multidc/scripts/run-multidc-nochao.sh async` | none | register + append | Knossos + Elle | ASYNC topology wiring |
| G | `multidc-sync-nochao` → `multidc/scripts/run-multidc-nochao.sh sync-voters` | none | register + append | Knossos + Elle | SYNC topology wiring |
| H | `witness-chaos` → `witness/scripts/run-witness-chaos.sh` | multidc + Witness overlay, nemesis ON | register + append | Knossos + Elle | Active/Hold/Witness region |
| I | `multidc-unclean-revive` → `multidc/scripts/run-multidc-unclean-revive.sh` | unclean long down on writer path | register + append | Knossos + Elle | cross-DC unclean revive |
| J | `1dc-swarm-chaos` → `scripts/run-jepsen-swarm.ps1` / `.sh` | partition, kill-proposer, **swarm-bounce** (follower kill/start); compose `docker-compose.swarm.yml` | append multi-mop TX | Elle | swarm enabled + auto-cutover under multi-key TX |
| K | `1dc-join-shards` → `scripts/run-jepsen-join.ps1` / `.sh` | partition + kill-proposer | **join** (Elle list-append via cross-shard `LEFT OUTER JOIN`) | Elle + honesty gate | JOIN + multi-shard parent/child under SQL TX |
| L | `multidc-async-swarm` → `multidc/scripts/run-multidc-swarm.sh` | DC-link + kill-voter + **swarm-bounce** (Active a*); configs `multidc/configs/async-swarm/` | append multi-mop TX | Elle | Multi-DC ASYNC_SHIP + swarm auto-cutover |
| M | `multidc-async-join` → `multidc/scripts/run-multidc-join.sh` | DC-link + kill-voter + kill/revive DC-A | **join** (Elle via cross-shard LEFT OUTER JOIN) | Elle + honesty gate | Multi-DC JOIN; Hold tip-fenced after Active loss (no `:ok` seed RPO reads) |

Matrix driver (1-DC A–K + Multi-DC D–G/I + L/M): `scripts/run-jepsen-all-profiles.ps1` (calm host, sequential). Failures → lab summary only (no floor cuts).

Each cell: fresh cluster → register → **fresh cluster** → append → stamp → non-zero exit on FAIL.
GHA: [`.github/workflows/jepsen-qg.yml`](../../.github/workflows/jepsen-qg.yml) matrix **A–M**.
Default `runs-on: ubuntu-latest`. Local runner: PR label **`self-hosted`** (optional extra PR labels `Windows` / `Linux` / `macOS` / `X64` / `ARM64`), or `workflow_dispatch` **`runner_labels`**, or repo var **`JEPSEN_RUNNER_LABELS`**. `github` / `ubuntu-latest` / `hosted` force GitHub-hosted. Push/nightly stay on `ubuntu-latest`. Jepsen still requires PR label `jepsen` to run at all.
Script flavor follows **`runner.os`**: Linux → bash (`.sh`); Windows → Windows PowerShell 5.1 (`powershell.exe`, not `pwsh`) for `run-jepsen-gha-cell.ps1` / `jepsen-env.ps1` / `dump-jepsen-cluster-logs.ps1`.
Triggers: `workflow_dispatch`, nightly, push main/master, PR label `jepsen`.

## In-process layer (not Docker Jepsen)

[`ci.yml`](../../.github/workflows/ci.yml) `mvn verify` includes `index.unit.replication.chaos.*IT`:

| IT / gap | Why Jepsen alone is not enough |
|----------|--------------------------------|
| `FollowerRestartCatchupIT`, `Partition*IT` | reconnect + ship |
| `OrchidUncleanRevive*IT` | characterization of unclean list/read |
| `OrchidTipBehindRestartIT` | tip vs OpLog after persist wipe |
| `RejoinAfterSealTruncateIT` | rejoin after seal+truncate gap (OpLog retired) |
| `RestartMidLoadIT`, `MidSealCrashIT`, `PitrRestoreIT` | seal / archive / hydrate |

## Critical FAIL loop

`:valid? false` / Elle `G*` / Knossos anomaly → save history → triage (`:ok` = product; `:info` spam = harness) → characterization IT → minimal fix → re-stamp. Never weaken quorum/fsync/living floors or checker model.

## Calm host session (local)

One session: `mvn verify` → matrix **A–M** sequentially → **full Load Tests**. No JMH / vs-OSS in this session.