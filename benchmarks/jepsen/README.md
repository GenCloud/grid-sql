# External Jepsen harness (F2)

Two contours share the same image (`jamoa-grid-jepsen:local`) and Clojure checkers (Knossos register / Elle append):

| Contour | Path | Topology | Entry |
|---------|------|----------|--------|
| **1-DC** | this tree (`docker-compose.yml`, `configs/`) | N=3 `n1`–`n3` | `scripts/run-jepsen*.ps1/sh`, `run-jepsen-nochao.*`, `run-smoke.*` |
| **2-DC (Multi-DC)** | [`multidc/`](multidc/) | 3+2 `a1`–`a3` + `b1`/`b2` | `multidc/scripts/run-multidc-async.*`, `run-multidc-sync-voters.*` (`-Full` / `MULTIDC_FULL=1` → real lein) |

Internal chaos ITs (`index.unit.replication.chaos`) remain the fast gate; this tree is the **external** consistency contour.

## Layout (essentials only)

| Path | Role |
|------|------|
| `Dockerfile`, `scripts/build-jepsen-image.*` | image (`jamoa-grid-jepsen:local`) |
| `docker-compose.yml`, `configs/` | 1-DC nodes `n1`–`n3` + optional `jepsen` control |
| `clojure/` (`project.clj`, `src/`) | Jepsen client/checker (**no** `store/` — gitignored) |
| `scripts/run-jepsen*.ps1/sh`, workload runners, nemesis helpers | 1-DC orchestration |
| `scripts/latency-from-history.ps1`, `qg-gate.ps1` | latency / QG from `history.edn` |
| `WORKLOAD.md`, `RESULTS.md` | 1-DC docs / stamps |
| `multidc/` | 2-DC compose + configs `{async,sync-voters}` + scripts + `RESULTS.md` |
| `docker-staging/` | host-jar build staging (only `.gitkeep` committed; `app.jar` gitignored) |

Do **not** commit `clojure/store/**`, root `*.log`, or `app.jar`.

## Prerequisites

| Path | Needs |
|------|--------|
| **Smoke** | Docker Compose v2; optional JDK 25 + Maven for chaos IT baseline |
| **Full Jepsen 1-DC / Multi-DC** | Docker + control container lein (or host Leiningen on Linux/WSL); cluster image from `Dockerfile` |

Clojure / Leiningen are **not** required for harness smoke. Full Jepsen uses the `control` compose profile (lein inside the control container) on this Windows host.

## Quick start — 1-DC smoke

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-smoke.ps1
```

## Quick start — 1-DC full (register + append)

```powershell
.\scripts\run-jepsen.ps1
# artifacts under clojure/store/ (gitignored) + RESULTS.md stamp
```

## Quick start — Multi-DC full (honest stamp)

```powershell
# ASYNC_SHIP then SYNC_VOTERS (each: compose up 5 nodes + lein register+append)
powershell -ExecutionPolicy Bypass -File .\multidc\scripts\run-multidc-async.ps1 -Full -TimeLimit 60
powershell -ExecutionPolicy Bypass -File .\multidc\scripts\run-multidc-sync-voters.ps1 -Full -TimeLimit 60
# stamps: multidc/RESULTS.md (PASS/FAIL + p50/p95/p99 when history exists)
```

Without `-Full` / `MULTIDC_FULL=1`: compose config validate only (scaffold).

Multi-host SQL URL (control / docs):

```
grid://grid:grid@a1:15432,a2:15433,a3:15434,b1:15435/public
```

Host-published equivalent: `grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public`.

**Harness vs prod write URL:** Jepsen may list a Hold endpoint for reconnect-after-claim. Prod with region fencing off should use the Active DC ring only; with region fencing on, dual-DC write URLs are allowed (`a1..a3,b1,b2`) and pin on `writerEligible && regionEpoch`. See [multidc/README.md](multidc/README.md), [ha-promote.md](../../docs/en/ha-promote.md).

See [multidc/README.md](multidc/README.md).

## Data volumes vs images (OpLog corrupt)

| Symptom | Cause | Fix |
|---------|--------|-----|
| `Corrupt op log record length=0` / `Failed replaying …/oplog/*.log` | Dirty **named volume** (`n*-data` / `multidc_*-data`) after crash or incomplete wipe | `scripts/jepsen-purge.ps1` / `jepsen-purge.sh` (Jepsen data volumes only) |
| Missing multidc configs / old jar behavior | Stale **image** `jamoa-grid-jepsen:local` | rebuild image; optional `jepsen-purge -PurgeImages` (Jepsen images only) |

**Scope rule:** purge touches **only** Jepsen Compose projects (`benchmarks/jepsen`, `benchmarks/jepsen/multidc`) — named volumes `jepsen_n*-data` / `multidc_*-data` and optionally `jamoa-grid-jepsen` images. **Never** `docker image prune`, `docker system prune`, or unrelated containers/volumes/images.

```powershell
# wipe 1-DC + Multi-DC Jepsen data volumes (keeps :local image and control m2)
powershell -File .\benchmarks\jepsen\scripts\jepsen-purge.ps1 -Scope all
# also drop non-:local jamoa-grid-jepsen images only (still no global prune)
powershell -File .\benchmarks\jepsen\scripts\jepsen-purge.ps1 -Scope all -PurgeImages
```

`run-jepsen.ps1` and `run-multidc-full.ps1` call purge before `compose up`.

## Fast image build

```powershell
.\scripts\build-jepsen-image.ps1
.\scripts\run-jepsen-nochao.ps1
```

## Nemesis (1-DC Compose)

```bash
./scripts/nemesis-partition.sh isolate n3
./scripts/nemesis-partition.sh heal
./scripts/nemesis-kill-proposer.sh
```

Multi-DC: `scripts/nemesis-dc-link.sh isolate|heal|kill-voter|kill-dc-a` (wired when `JEPSEN_MULTIDC=1`). `kill-dc-a` stops/restarts whole DC-A (`a1`+`a2`+`a3`) for Active→Hold claim chaos.

## GitHub Actions (Jepsen QG)

Separate workflow [`.github/workflows/jepsen-qg.yml`](../../.github/workflows/jepsen-qg.yml) — **not** unit CI (`.github/workflows/ci.yml`). Never co-run with Load / JMH / OSS compare-all.

| Matrix `config` | Entrypoint | What it gates |
|-----------------|------------|---------------|
| `1dc-chaos` | `scripts/run-jepsen.sh` | 1-DC register+append with nemesis (`:valid?`) |
| `1dc-nochao` | `scripts/run-jepsen-nochao.sh` + `qg-gate.ps1` | No-nemesis consistency + Ref B latency (p50/p95 hard) |
| `multidc-async` | `multidc/scripts/run-multidc-async.sh` (`MULTIDC_FULL=1`) | 2-DC ASYNC_SHIP register+append |
| `multidc-sync` | `multidc/scripts/run-multidc-sync-voters.sh` (`MULTIDC_FULL=1`) | 2-DC SYNC_VOTERS register+append |
| `witness` | `witness/scripts/run-witness-chaos.ps1` | Active+Hold+Witness chaos stamp |

**Triggers:** `workflow_dispatch` (input `time_limit`, default 60), nightly `schedule`, `push` to `main`/`master`. On **PR** only when label `jepsen` is present (or run via Actions → workflow_dispatch).

**Artifacts:** each matrix cell uploads `RESULTS.md`, `ARTIFACTS.txt` (nochao), and `clojure/store/**/{history,results}.edn` when present. Shared image `jamoa-grid-jepsen:local` is built once and loaded per cell (`JEPSEN_REBUILD=0` / `MULTIDC_SKIP_REBUILD=1`).

**Local pre-commit smoke** (calm host; one contour at a time):

```bash
export JEPSEN_TIME_LIMIT=30 JEPSEN_REBUILD=1
./benchmarks/jepsen/scripts/build-jepsen-image.sh
./benchmarks/jepsen/scripts/jepsen-purge.sh all
./benchmarks/jepsen/scripts/run-jepsen-nochao.sh
# then: source benchmarks/jepsen/ARTIFACTS.txt && pwsh -File benchmarks/jepsen/scripts/qg-gate.ps1 ...
```

Optional: `actionlint .github/workflows/jepsen-qg.yml` and `act workflow_dispatch -W .github/workflows/jepsen-qg.yml -j build-image`.

## Relation to internal chaos

| Contour | Where | What |
|---------|--------|------|
| Internal | `grid-server-core` → `index.unit.replication.chaos` | In-process partition / linearizability |
| External 1-DC | this tree | Docker N=3 + nemesis + Knossos/Elle |
| External Multi-DC | `multidc/` | Docker 3+2 + DC-link chaos + Knossos/Elle |

Claim only after a green full Jepsen run stamped in `RESULTS.md` / `multidc/RESULTS.md`. Smoke alone is **HARNESS_READY**, not a consistency claim.
