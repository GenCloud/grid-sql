# Multi-DC Jepsen harness

External consistency contour for **2-DC** ORCHID + `CrossDcPublisher` (+ optional Active/Hold region fencing). Complements the 1-DC N=3 tree in `../` (same image `jamoa-grid-jepsen:local`, same Clojure checker).

**Stamp status:** see [RESULTS.md](RESULTS.md) - `2026-09-17-multidc-*` (ASYNC_SHIP **PASS** + SYNC_VOTERS **PASS**). Full path: `-Full` / `MULTIDC_FULL=1`.

Docs: [cluster-multidc-highload.md](../../../docs/en/cluster-multidc-highload.md), [ha-promote.md](../../../docs/en/ha-promote.md).

## Topology (default 3+2)

| DC | Nodes | Role |
|----|-------|------|
| **dc-a** | `a1`, `a2`, `a3` | Active local ORCHID write quorum (majority of 3) |
| **dc-b** | `b1`, `b2` | Hold / remote: `b1` sync voter (SYNC mode) or async peer; `b2` learner / async |

### Harness URL vs prod write URL

| Audience | URL | Why |
|----------|-----|-----|
| **Jepsen harness** (control) | `grid://@a1:15432,a2:15433,a3:15434,b1:15435/public` | Discovers sticky via wire `ServerMeta`; may include a Hold endpoint for reconnect after claim |
| **Prod write (region fencing off)** | `grid://u:p@a1:15432,a2:15433,a3:15434/public?...` | Active DC ring only |
| **Prod write (region fencing on)** | `grid://u:p@a1:15432,a2:15433,a3:15434,b1:15435,b2:15436/public?...` | Dual-DC authority; pin on `writerEligible && regionEpoch` |

Do not treat the harness authority list as a sizing template for apps that omit region fencing — Hold/learner TCP stays up but mutate rejects until Active (or a successful claim).

| Node | HTTP | SQL | Replication |
|------|------|-----|-------------|
| a1 | 7777 | 15432 | 5615 |
| a2 | 7778 | 15433 | 5616 |
| a3 | 7779 | 15434 | 5617 |
| b1 | 7780 | 15435 | 5618 |
| b2 | 7781 | 15436 | 5619 |

Compose networks: `dc-a`, `dc-b`, `dc-link` (cross-DC).

## Purge volumes (OpLog length=0)

Corrupt OpLog on boot = dirty **data volumes**, not images. From repo root:

```powershell
powershell -File ..\scripts\jepsen-purge.ps1 -Scope multidc
# or from multidc/scripts via run-multidc-full (calls purge before up)
```

See parent [README.md](../README.md) table (data vs image).

## Workloads

| Mode | Config dir | Commit path | Expectation |
|------|------------|-------------|-------------|
| **ASYNC_SHIP** | `configs/async/` | Local ORCHID + OpLog; async ship to dc-b | Non-zero RPO across WAN; local DC keeps writing under DC-link partition |
| **SYNC_VOTERS_ACROSS_DC** | `configs/sync-voters/` | Local ORCHID waits remote digest ACK from `b1` | Lower RPO on voter; commits stall/fail when `b1` unreachable |

Chaos (`JEPSEN_MULTIDC=1` → `nemesis-dc-link.sh`):

1. **DC-link partition** — detach `b1`/`b2` from `dc-link`
2. **Kill remote voter** — restart `b1` (SYNC_VOTERS; skipped in ASYNC)
3. **Kill whole DC-A** — stop/restart `a1`+`a2`+`a3` (`kill-dc-a`) to exercise Active loss → Hold claim / `regionEpoch` advance when region fencing is on

## Layout

| Path | Role |
|------|------|
| `docker-compose.yml` | 5 nodes + optional control |
| `configs/async/` | `cross-dc.mode: ASYNC_SHIP` |
| `configs/sync-voters/` | `SYNC_VOTERS_ACROSS_DC`, voters `[b1]`, learners `[b2]` |
| `scripts/run-multidc-full.ps1` | Shared Docker+lein + latency + RESULTS stamp |
| `scripts/run-multidc-async.{ps1,sh}` | ASYNC_SHIP entry |
| `scripts/run-multidc-sync-voters.{ps1,sh}` | SYNC_VOTERS entry |
| `RESULTS.md` | Honest stamp log |

## Quick start

```powershell
# Scaffold (compose config only)
.\scripts\run-multidc-async.ps1
.\scripts\run-multidc-sync-voters.ps1

# Full Docker + lein (Windows host; lein inside control container)
.\scripts\run-multidc-async.ps1 -Full -TimeLimit 60
.\scripts\run-multidc-sync-voters.ps1 -Full -TimeLimit 60
```

```bash
MULTIDC_FULL=1 ./scripts/run-multidc-async.sh
MULTIDC_FULL=1 ./scripts/run-multidc-sync-voters.sh
```

Prerequisites: Docker Compose v2; image via `../scripts/build-jepsen-image.ps1` (or `.sh`). Host Leiningen not required when using the control profile.

## Relation to 1-DC Jepsen

| Contour | Path | Claim |
|---------|------|-------|
| 1-DC N=3 | `benchmarks/jepsen/` | Latest stamp in [../RESULTS.md](../RESULTS.md) |
| Multi-DC 3+2 | this tree | Stamp in [RESULTS.md](RESULTS.md) after `-Full` |

Do not treat local JMH SYNC vs ASYNC as a Multi-DC Jepsen substitute.
