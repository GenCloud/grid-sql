# System requirements (low / mid / high)

Compose `env/low|mid|high.env` change **JVM heap only**. This page turns those tiers into **host RAM / CPU / disk** budgets per topology.

Figures are **planning floors**, not a guarantee of living TPS. Throughput ceilings stay in [capacity SLO](../../performance/capacity-slo.md). Re-measure after dataset growth or WAN.

## Evidence baseline (1-DC N=3, mid)

Measured mid tier (`-Xmx2g`), `hydrate-mode: LAZY`, `fsync: true`, idle after light smoke traffic.

| Signal | Writer | Replica A | Replica B |
|--------|-------:|----------:|----------:|
| Process RSS | ≈1.17 GiB | ≈1.11 GiB | ≈1.10 GiB |
| Heap max (`G1 Old Gen` max) | 2 GiB | 2 GiB | 2 GiB |
| Non-heap used | ≈85–90 MiB | ≈85–90 MiB | ≈85–90 MiB |
| Working set | 0 / 65536 | 0 / 65536 | 0 / 65536 |
| `dataDir` total | ≈842 MiB | ≈828 MiB | ≈822 MiB |
| OpLog | ≈839 MiB | ≈825 MiB | ≈819 MiB |
| Sealed | ≈2.9 MiB | ≈2.9 MiB | ≈2.9 MiB |
| Process CPU (fraction of host) | ≈0.06 | ≈0.06 | ≈0.06 |
| `orchid_r` | ≈1.0 | ≈1.0 | ≈1.0 |

Cluster RSS sum ≈ **3.3 GiB** for three mid nodes. Host class of the measurement matches the [capacity SLO](../../performance/capacity-slo.md) lab host (≈64 GiB RAM, 16 logical CPUs).

**Read of the baseline:** idle mid nodes sit near **≈1.1 GiB RSS** with a **2 GiB** heap cap; disk is dominated by the **OpLog tail** until seal truncates; sealed corpus can be tiny while the journal is large. Plan disk for journal + sealed + archive, not sealed alone.

## Load tiers (JVM)

Same knobs as `examples/compose/*/env/*.env`:

| Tier | `JAVA_TOOL_OPTIONS` heap | Intended use |
|------|--------------------------|--------------|
| **low** | `-Xms512m -Xmx1g` | Smoke, CI, empty/small catalog |
| **mid** | `-Xms1g -Xmx2g` | Dev / everyday HA (matches the baseline above) |
| **high** | `-Xms2g -Xmx4g` (+ G1) | Load / QG / living-floor attempts on a calm host |

RSS under load is **not** equal to `Xmx`. Keep host RAM ≈ **1.5×–2× `Xmx` per node** so GC, Metaspace, Netty/direct buffers, and sealed mmap windows fit. Filled `working-set-max-entries` (default adaptive **65536**) adds map/index RAM on top of the idle baseline.

## Per-node budget (one Grid process)

| Resource | low | mid | high |
|----------|-----|-----|------|
| Heap (`Xmx`) | 1 GiB | 2 GiB | 4 GiB |
| Host RAM for the process | ≥2 GiB | ≥3–4 GiB | ≥6–8 GiB |
| vCPU | ≥2 | ≥4 | ≥8 |
| Local NVMe (OS + `dataDir` free) | ≥10 GiB | ≥20 GiB | ≥50 GiB + dataset×2 |
| Disk type | SSD | NVMe | NVMe (fsync path) |
| JDK | 25 + `--enable-preview` | same | same |

CPU note: durable write TPS is bounded by **ORCHID + OpLog fsync**, not spare cores. Extra CPU helps AQE/scans and keeps ORCHID ticks off the critical path; it does not double WRITE floors. Living HA floors were measured on ~10 cores / 16 threads for **two** nodes — [capacity SLO](../../performance/capacity-slo.md).

Disk note: each voter keeps its **own** `dataDir` (no shared NFS). Size for OpLog tail + sealed generations + optional PITR archive. Baseline ~0.8 GiB OpLog with ~3 MiB sealed is normal before truncate — do not size production as “sealed only”.

## By topology (cluster total)

Multiply the per-node row by process count. Witness (multi-site) is a full JVM with the same tier unless you deliberately shrink heap for vote-only duty — still budget **≥ mid** RAM so claim quorum stays up.

| Topology | Processes | low (RAM / vCPU / disk free) | mid | high |
|----------|----------:|------------------------------|-----|------|
| Solo durable (`capacity`) | 1 | ≥2 GiB / 2 / 10 GiB | ≥4 GiB / 4 / 20 GiB | ≥8 GiB / 8 / 50 GiB+ |
| HA 1+1 (`primary`+`replica`) — Load living floors | 2 | ≥4 GiB / 4 / 20 GiB | ≥8 GiB / 8 / 40 GiB | ≥16 GiB / 16 / 100 GiB+ |
| HA 1-DC N=3 (everyday HA / hand-off) | 3 | ≥6 GiB / 6 / 30 GiB | ≥12 GiB / 12 / 60 GiB | ≥24 GiB / 24 / 150 GiB+ |
| Multi-DC ASYNC (3+2+Witness) | 6 | ≥12 GiB / 12 / 60 GiB | ≥24 GiB / 24 / 120 GiB | ≥48 GiB / 48 / 300 GiB+ |
| Multi-DC SYNC (3+2+Witness) | 6 | same RAM/CPU/disk as ASYNC | same | same; plus **low WAN RTT** for digest voters |

Cluster totals are the **sum of node budgets**, or **per-VM** when nodes are split. Three mid nodes need ≈12 GiB planning RAM for Grid; the idle baseline above used ≈3.3 GiB RSS with OS headroom on a 64 GiB-class host.

```mermaid
flowchart LR
  Solo["Solo capacity\n1 × node"] --> HA11["HA 1+1\n2 × node"]
  HA11 --> N3["1-DC N=3\n3 × node"]
  N3 --> MDC["Multi-DC 3+2+W\n6 × node"]
```

*Figure 1. Process count scales the same per-node tier.*

## Ports and network

| Path | Default ports | Note |
|------|---------------|------|
| SQL | 15432+ | Client `grid://` / tooling JDBC |
| Replication | 5615+ | Never publish publicly; not SQL |
| Actuator | 7777+ | Readiness / Prometheus (profile may remap) |

Multi-DC **SYNC_VOTERS_ACROSS_DC** adds cross-site RTT to every write; size WAN and remote voters before raising the heap tier. ASYNC keeps commit local and pays RPO instead.

## What these numbers are not

| Claim | Reality |
|-------|---------|
| “low/mid/high = living TPS tiers” | Tiers size the JVM/host; TPS floors need a calm Load stamp on **primary+replica** |
| “N=3 mid will hit WRITE≈4442” | Living WRITE/READ/CAPACITY floors are defined on starter **1+1**, not on N=3 — [capacity SLO](../../performance/capacity-slo.md) |
| “Disk = sealed size” | OpLog can dominate until seal+truncate; archive grows without rotation |
| “Idle RSS = production RAM” | Fill working set, raise client concurrency, then re-check process RSS and `ws_size` |

## How to re-stamp

1. Start the topology; wait readiness + one `writerEligible=true`.
2. Record per node: process RSS, `jvm_memory_*`, `grid_durability_ws_*`, `dataDir` du (oplog vs sealed).
3. Optional: calm Load on **1+1** only when claiming living floors.
4. Update this page’s evidence table when host class or default heap tiers change.

Compose heap toggles: [deploy compose](deploy-compose.md). Go-live checks: [production checklist](../../getting-started/production-checklist.md).

Russian: [system-requirements.md](../../../ru/configure-and-operate/operations/system-requirements.md).
