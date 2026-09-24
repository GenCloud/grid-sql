# JMeter load runs

Capacity load is driven by **Apache JMeter** over `grid-sql-client` and `grid://`. JDBC and the synchronous helpers (`JdbcSync`, `SyncAwait`) are not used in these runs, because the sync bridge distorts latency; in application services JDBC remains fine.

The planning figures and the ~95% regression floors live in [capacity](../performance/capacity-slo.md). Here: how to run the load and what makes a run citable.

## What you need

| | |
|--|--|
| Driver | Module `grid-sql-jmeter` plus `scripts/run-jmeter-load-slo.ps1` |
| Target | Writer SQL **15432** (`primary` + `replica`); reads follow the plan mix |
| Valid run | Calm host, CLI `-n`, one run at a time |
| Output | Experiments as `*-load-slo.json` under `grid-server-core/benchmarks/lab/`, HTML and CSV under `grid-sql-jmeter/target/jmeter-run/`; curated capacity runs in [`SUMMARY.md`](../../../grid-server-core/benchmarks/results/SUMMARY.md) |

Claimed HA figures come from `primary` + `replica` with `fsync: true` on SQL **15432**/**15433**. A single durable node (`capacity` profile) is a separate figure and is never mixed into the HA numbers.

## How to run

1. Bring up the topology, local pair or compose: [start a cluster](../getting-started/start-cluster.md), [deploy compose](../configure-and-operate/operations/deploy-compose.md).
2. Confirm the host is not running QG, Jepsen or JMH.
3. Start the load from the CLI:

```powershell
powershell -File .\scripts\run-jmeter-load-slo.ps1 -Stamp <run-id> -Clients 48 -MixProfile WRITE_ONLY
powershell -File .\scripts\run-jmeter-load-slo.ps1 -Stamp <run-id> -Clients 128 -MixProfile READ_ONLY
powershell -File .\scripts\run-jmeter-load-slo.ps1 -Stamp <run-id> -Clients 128 -MixProfile CAPACITY -Profile capacity
```

| Flag | Meaning |
|------|---------|
| `-MixProfile` | Sampler weights: `WRITE_ONLY`, `READ_ONLY`, `CAPACITY`, `CHAOS`, … |
| `-Profile` | `KEY_SPACE` only: `capacity` (1 000 000) or `contention` (10 000) |
| `-Clients` | Thread count; the useful range is usually 64–128 |
| `-DurationSec` | Duration; chaos and stress mixes need longer windows |
| `-Stamp` | Run id used to name the output files |

Reports: Aggregate and Summary inside the plan, the HTML dashboard (`-e -o`), and CSV under `grid-sql-jmeter/target/jmeter-run/{runId}-reports/`. The machine-readable file is `*-load-slo.json`.

### GUI mode (plan debugging only)

```powershell
powershell -File .\scripts\stage-jmeter-classpath.ps1
# → grid-sql-jmeter/target/jmeter-user.classpath.txt
```

GUI mode needs JDK **25** with `--enable-preview`, and `ResultCollector` must have a `Filename`. Numbers produced in the GUI are for plan debugging only; anything quoted as a planning figure comes from CLI `-n`.

## Limits

- Running alongside another check invalidates the numbers; discard the run.
- `fsync: false` isolates a bottleneck in the lab and never becomes a threshold.
- JDBC and the IDE driver are not used for load.
- Figures belong to the lab host described in capacity; another topology needs its own run.

## Related

- [Capacity and thresholds](../performance/capacity-slo.md) — tables, bands, planning figures
- [Methodology](../performance/methodology.md) — check order and abort rules
- [Results](../performance/results.md) — JMH and consistency
- [Best practices](../getting-started/best-practices.md)

Russian: [jmeter-load-slo.md](../../ru/tools/jmeter-load-slo.md).
