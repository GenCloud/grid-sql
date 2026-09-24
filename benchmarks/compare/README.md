# Side-by-side compare harness

Latest quality-gate stamp: `2026-09-17-async-pass` / `2026-09-17-roadmap-p3` (QG Overall PASS + Jepsen 1-DC full PASS; Multi-DC ASYNC+SYNC PASS — see `benchmarks/jepsen/multidc/RESULTS.md`).

Fair latency comparisons on one host. Durability matched (fsync/sync on).

## Layout

Latest quality-gate stamp: `2026-09-17-async-pass` / `2026-09-17-roadmap-p3` (QG Overall PASS + Jepsen 1-DC full PASS; Multi-DC ASYNC+SYNC PASS — see `benchmarks/jepsen/multidc/RESULTS.md`).

| Path | Role |
|------|------|
| `docker-compose.yml` | etcd 1-node, Redis (+replica), profile `cluster3` etcd-3 |
| `docker-compose.toxiproxy.yml` | profile `wan` toxiproxy (`MultiDcToxiproxyApiIT` via :8474; DelayedTcpProxy for offline JMH) |
| `../../scripts/run-compare-etcd.{ps1,sh}` | etcd 1-node put → JSON |
| `../../scripts/run-compare-etcd-3.{ps1,sh}` | etcd 3-node put → JSON |
| `../../scripts/run-compare-redis.{ps1,sh}` | Redis SET + replica lag via **host Jedis** → JSON |
| `../../scripts/run-compare-orchid.{ps1,sh}` | ORCHID durable JMH |
| `../../scripts/run-compare-wal.{ps1,sh}` | OpLog vs RocksDB JMH |
| `../../scripts/run-compare-encode.{ps1,sh}` | logical/duplex vs Kryo/FST |
| `../../scripts/run-compare-query.{ps1,sh}` | large filter/ORDER BY vs H2 |
| `../../scripts/run-compare-all.{ps1,sh}` | **full matrix** + `SUMMARY.md` |

JMH tracks (via `run-compare-all`): orchid, wal, encode, query, MultiDcVoters (incl. delayed ACK), PlacementOptimizer, Hazelcast.

## Full suite

Latest quality-gate stamp: `2026-09-17-async-pass` / `2026-09-17-roadmap-p3` (QG Overall PASS + Jepsen 1-DC full PASS; Multi-DC ASYNC+SYNC PASS — see `benchmarks/jepsen/multidc/RESULTS.md`).

```powershell
./scripts/run-compare-all.ps1
```

Produces `grid-server-core/benchmarks/results/SUMMARY.md` with PASS/FAIL gates.

Consistency (Jepsen register+append) is a **separate** harness: `benchmarks/jepsen/` — latest stamp in `benchmarks/jepsen/RESULTS.md` (PASS only). Algorithm latency gate: `scripts/run-jepsen-nochao.{ps1,sh}` → row `latency-nochao` (chaos p99 is not a gate).

CI: manual job `compare-oss` in `.gitlab-ci.yml`.

Use `benchmarks/compare/maven-central-settings.xml` if corporate mirrors hang.

## Fast smoke (not a QG stamp)

Tracks always run **sequentially** on one host — never parallelize JMH with Jepsen/JMeter/OSS peers.

```powershell
./scripts/run-compare-all.ps1 -Fast
```

Sets `JMH_FAST=1` (shorter warmup/measure), `SEALED_BENCH_ROWS=1000`, skips etcd/redis/hazelcast/multi-dc/sql-* tracks. Re-run **without** `-Fast` for SUMMARY / QG.
