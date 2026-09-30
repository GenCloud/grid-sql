# Jepsen coverage matrix (ORCHID / recovery / consistency)

Honest mapping: **invariant → fault → workload → checker → CI job → stamp**.
Do not claim PASS from scaffold-only or `--no-nemesis` when the cell is labeled chaos.

## FAQ: `[:r k nil]` in logs

Elle mop `[:r k nil]` on **`:invoke`** means "read key k"; `nil` is a **placeholder**, not a NULL row or consensus bug.
On **`:ok`** the value is the token list (e.g. `[:r 16 ["t14" ...]]`). Anomalies are reported by Elle/Knossos, not by `nil` in invoke.

## Matrix (full bash entrypoints)

| ID | `matrix.config` / entrypoint | Nemesis | Workloads | Checker | Invariants covered |
|----|------------------------------|---------|-----------|---------|-------------------|
| A | `1dc-chaos` → `scripts/run-jepsen.sh` | partition, kill-proposer, kill/revive-dc-a (1-DC skips DC-A) | register + append | Knossos + Elle | ORCHID admit, sticky writer, heal catch-up |
| B | `1dc-unclean-revive` → `scripts/run-jepsen-unclean-revive.sh` | long proposer down, no purge | register + append | Knossos + Elle | unclean revive, sticky/rediscover, list monotonicity |
| C | `1dc-nochao` → `scripts/run-jepsen-nochao.sh` + `qg-gate.sh` | none | register + append | Knossos + Elle + Ref B latency | baseline consistency + p50 (p95 advisory on GHA) |
| D | `multidc-async-chaos` → `multidc/scripts/run-multidc-chaos.sh async` | DC-link partition/heal, kill-voter, kill/revive DC-A | register + append | Knossos + Elle | ASYNC_SHIP under faults |
| E | `multidc-sync-chaos` → `multidc/scripts/run-multidc-chaos.sh sync-voters` | same | register + append | Knossos + Elle | SYNC_VOTERS under faults |
| F | `multidc-async-nochao` → `multidc/scripts/run-multidc-nochao.sh async` | none | register + append | Knossos + Elle | ASYNC topology wiring |
| G | `multidc-sync-nochao` → `multidc/scripts/run-multidc-nochao.sh sync-voters` | none | register + append | Knossos + Elle | SYNC topology wiring |
| H | `witness-chaos` → `witness/scripts/run-witness-chaos.sh` | multidc + Witness overlay, nemesis ON | register + append | Knossos + Elle | Active/Hold/Witness region |
| I | `multidc-unclean-revive` → `multidc/scripts/run-multidc-unclean-revive.sh` | unclean long down on writer path | register + append | Knossos + Elle | cross-DC unclean revive |

Each cell: fresh cluster → register → **fresh cluster** → append → stamp → non-zero exit on FAIL.
GHA: [`.github/workflows/jepsen-qg.yml`](../../.github/workflows/jepsen-qg.yml) (PR label `jepsen`, push main/master, nightly, workflow_dispatch).

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

One session: `mvn verify` → matrix A–I sequentially → **full Load Tests**. No JMH / vs-OSS in this session.