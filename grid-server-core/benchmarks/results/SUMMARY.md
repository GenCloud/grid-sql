# Top load runs (lab)

Curated machine stamps that back the planning numbers in [capacity-slo](../../../docs/en/performance/capacity-slo.md).
Product JMH tables live in [results](../../../docs/en/performance/results.md). This folder keeps **only** these TOP `*-load-slo.json` files — not campaign diaries.

| Role | Stamp | Threads | Window | TPS | p95 | File |
|------|-------|--------:|-------:|----:|----:|------|
| WRITE_ONLY (HA) | `2026-09-20-ha-write` | 48 | 40 s | **4921.975** | 14 ms | `2026-09-20-ha-write-load-slo.json` |
| READ_ONLY (HA) upper | `2026-09-20-ooo-fix-load-read` | 64 | 45 s | **59430.467** | 1 ms | `2026-09-20-ooo-fix-load-read-load-slo.json` |
| READ_ONLY (HA) lower | `2026-09-21-cutover-squeeze-read` | 64 | 45 s | **52260.578** | 1 ms | `2026-09-21-cutover-squeeze-read-load-slo.json` |
| Capacity QG (HA) upper | `2026-09-21-cutover-squeeze-r2-qg` | 64 | 45 s | **11351.533** | 16 ms | `2026-09-21-cutover-squeeze-r2-qg-load-slo.json` |
| Capacity QG (HA) lower | `2026-09-21-cutover-squeeze-r6-qg` | 64 | 45 s | **8772.022** | 31 ms | `2026-09-21-cutover-squeeze-r6-qg-load-slo.json` |
| HA mix | `2026-09-20-ha-mix-r2` | 128 | 120 s | **9013.292** | 43 ms | `2026-09-20-ha-mix-r2-load-slo.json` |
| WRITE (solo) | `2026-09-20-single-write` | 48 | 40 s | **7095.325** | 8 ms | `2026-09-20-single-write-load-slo.json` |
| QG (solo) | `2026-09-21-single-qg` | 64 | 45 s | **19526.133** | 7 ms | `2026-09-21-single-qg-load-slo.json` |
| READ (solo) | `2026-09-20-single-read` | 64 | 45 s | **56563.533** | 1 ms | `2026-09-20-single-read-load-slo.json` |
| Chaos | `2026-09-18-chaos` | 32 | 40 s | **301.7** | 138 ms | `2026-09-18-chaos-load-slo.json` |

Host and ~95% thresholds: [capacity-slo](../../../docs/en/performance/capacity-slo.md). Consistency stamps: [Jepsen 1-DC](../../../benchmarks/jepsen/RESULTS.md), [Multi-DC](../../../benchmarks/jepsen/multidc/RESULTS.md).