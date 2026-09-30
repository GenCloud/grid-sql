# Capacity and load thresholds

On the lab host (two nodes, `fsync: true`) Grid sustains roughly **4676** writes/s, **59430** reads/s (band **52261…59430**) and **8772…11519** mixed ops/s (HA mix ≈**9896**/s). The formal regression floor is ~95% of the planning figure — for a band, of its lower bound. Measure on a calm host, one check at a time, over `grid://`.

Load is driven by **Apache JMeter** through `grid-sql-client`. JDBC is fine in applications, but capacity runs use `grid://` only: the synchronous bridge distorts latency. Jepsen checks consistency, not throughput.

## What we measure

| Profile | Query mix | Shows |
|---------|-----------|-------|
| **WRITE_ONLY** | UPSERT 100% | Durable write ceiling: OpLog + `fsync` + ORCHID |
| **READ_ONLY** | EQ 90% / JOIN 10% | Index read and simple join ceiling |
| **Capacity QG** | EQ 50 / UPSERT 40 / JOIN 10 | Mixed load |
| **Chaos** | same + 20% short TX | Lock conflicts |
| **Stress** | write-heavy + few short TX | Overload |

There is also an **HA mix sizing** run: the Capacity mix at 120 s / 128 threads, used for sizing rather than as a record.

| Server profile | Meaning | Counts for thresholds |
|----------------|---------|-----------------------|
| **`primary` + `replica`** | HA: ORCHID, replica, **`fsync: true`** | **Yes** — the claimed HA maximum |
| **`capacity`** | Solo durable node, no replication | Yes, kept separate from HA |
| `fsync: false` | Lab isolation without the OpLog cost | **No** |

For READ and QG the planning figure is an observation band, because host noise at that rate is normal. The formal threshold is ≈95% of the planning figure, or of the band's lower bound.

## How to measure

Calm host, JMeter CLI (`-n`). Never co-run load with QG, Jepsen or JMH. Check order: [methodology](methodology.md).

```powershell
# -Stamp names the output files under grid-server-core/benchmarks/lab/
powershell -File .\scripts\run-jmeter-load-slo.ps1 -Stamp <run-id> -Clients 128 -MixProfile WRITE_ONLY
powershell -File .\scripts\run-jmeter-load-slo.ps1 -Stamp <run-id> -Clients 128 -MixProfile READ_ONLY
powershell -File .\scripts\run-jmeter-load-slo.ps1 -Stamp <run-id> -Clients 64 -MixProfile CAPACITY
```

`-Profile` sets `KEY_SPACE` only (`capacity` = 1M keys, `contention` = 10k). All flags: [JMeter](../tools/jmeter-load-slo.md).

### Lab host for these numbers

| | |
|--|--|
| OS | Windows 10 Pro 10.0.18362 |
| CPU | Intel Core 7 240H, 10 cores / 16 threads |
| RAM | ≈64 GiB |
| Disk | NVMe SSD; `dataDir` on local `D:` |
| JDK | Temurin 25 (`--enable-preview`) |
| HA topology | `primary` + `replica` on localhost, `fsync: true` |

The numbers are bound to this hardware. Cloud, WAN and multi-site deployments need their own run.

## Planning numbers

HA (`primary` + `replica`, `fsync: true`): write **4676**/s, read **52261…59430**/s, QG **8772…11519**/s, HA mix **9896**/s. Thresholds: ≈**4442** / ≈**52261** / ≈**8333**.

Solo (`capacity`): write **7095**/s, QG **19526**/s, read **56564**/s.

| Track | Threads | Window | TPS | p95 | Threshold (~95%) |
|-------|--------:|-------:|----:|----:|-----------------:|
| WRITE_ONLY (HA) | 48 | 40 s | **4676** | 15 ms | ≈**4442** |
| READ_ONLY (HA) | 64 | 45 s | **52261…59430** | 1 ms | ≈**52261** |
| Capacity QG (HA) | 64 | 45 s | **8772…11519** | 16…31 ms | ≈**8333** |
| HA mix | 128 | 120 s | **9896** | 38 ms | — |
| WRITE (solo) | 48 | 40 s | **7095** | 8 ms | — |
| QG (solo) | 64 | 45 s | **19526** | 7 ms | — |
| READ (solo) | 64 | 45 s | **56564** | 1 ms | — |
| Chaos | 32 | 40 s | ≈302 | 138 ms | errors ≤0.15 |

Doubling these figures is not claimed: every run uses the default `WRITE_BATCH_SIZE=1`.

## Next

- [Methodology](methodology.md) — what makes a run valid
- [Results](results.md) — JMH, sealed path, commit latency
- [JMeter](../tools/jmeter-load-slo.md) — flags and reports
- [Failures](../configure-and-operate/operations/failures.md) and [best practices](../getting-started/best-practices.md)
- Consistency: [Jepsen 1-DC](../../../benchmarks/jepsen/RESULTS.md), [Multi-DC](../../../benchmarks/jepsen/multidc/RESULTS.md)

Russian: [capacity-slo.md](../../ru/performance/capacity-slo.md).
