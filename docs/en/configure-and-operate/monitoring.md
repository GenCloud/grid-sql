# Monitoring

A node publishes its state through Spring Actuator health endpoints and Micrometer metrics. Orchestrators use the health probes; dashboards and alerts use the metrics. Neither surface is used for writer discovery — clients learn the writer from wire metadata, described in [role promotion](operations/ha-promote.md).

## Endpoints

The starter profiles place Actuator at the root (`management.endpoints.web.base-path: /`) with `show-details: always` and Kubernetes probe groups enabled.

| Endpoint | Starter layout | Default Spring layout | Use |
|----------|----------------|-----------------------|-----|
| Liveness | `/health/liveness` | `/actuator/health/liveness` | Process and logic executor alive; container restart decisions |
| Readiness | `/health/readiness` | `/actuator/health/readiness` | Safe to receive traffic |
| Metrics | `/prometheus` | `/actuator/prometheus` | Micrometer scrape |
| Build info | `/info` | `/actuator/info` | Version identification during upgrades |

Readiness stays DOWN while replication is enabled and consensus has not synced. That is intentional: the probe does not claim a node is ready to take writes before it can admit them.

```bash
curl -s http://127.0.0.1:7777/health/readiness | jq '.components.gridReadiness.details'
# Replica / capacity starter profiles use Actuator on 7778:
# curl -s http://127.0.0.1:7778/health/readiness | jq '.components.gridReadiness.details'
```

## Readiness details

The `gridReadiness` component reports the following keys. Values marked `n/a` mean the subsystem is not enabled on this node.

| Key | Values | Meaning |
|-----|--------|---------|
| `sqlTcp` | `listening`, `disabled`, `down` | SQL listener state |
| `durableOrSynced` | `up`, `n/a` | Local durability or consensus sync is sufficient for UP |
| `orchidSynced` | `true`, `false`, `n/a` | Consensus phase sync reached |
| `reason` | `sql_tcp_down`, `orchid_not_synced` | Present only when the component is DOWN |
| `writerEligible` | boolean, `n/a` | This node may accept writes |
| `applyLagStale` | boolean, `n/a` | Apply lag exceeds `grid.replication.ha.max-stale-lag` |
| `orchidR` | double, `n/a` | Phase order parameter |
| `repairIssued` / `repairApplied` | long, `n/a` | Gap repair counters |
| `rpoEstimateMs` | long, `n/a` | Cross-site lag estimate |
| `swarmHint` | `KEEP`, `ATTRACT_LEARNER`, `SHED_LOAD`, `PREFER_DC`, `n/a` | Latest placement hint, by name |
| `maxTxContexts` | integer | May appear when `grid.sql.max-tx-contexts` is set in YAML; Boot does **not** apply it to the TCP listener (hard channel cap stays **8**) — [SQL server](configuration/sql-server.md) |
| `lockWaitTimeouts`, `lockCancels`, `sqlCancelInflight` | long | Record-lock and cancellation counters |

Liveness (`gridLiveness`) reports `logicExecutor`, `uptimeMs`, `pid`, and the same consensus and repair gauges. It has no `reason` key: a live process with an unsynced cluster is alive but not ready.

The placement hint appears as a name in health details and as an ordinal in Micrometer (`-1` when absent). Do not compare the two directly.

## Metrics

Consensus and replication:

| Metric | Use |
|--------|-----|
| `grid.replication.orchid_r` | Phase order parameter; should stay above `orchid.order-threshold` |
| `grid.replication.orchid_wait_p99_ns` | Time spent waiting for phase sync and digest quorum |
| `grid.replication.oplog_fsync_p99_ns` | Grouped journal `force` latency |
| `grid.replication.repair_issued` / `repair_applied` | Gap repair; issued without applied means catch-up is stuck |
| `grid.replication.rpo_estimate_ms` | Cross-site lag estimate |
| `grid.replication.oplog_push_sent` / `oplog_push_recv` | Journal shipping volume |
| `grid.replication.apply_ack_sent` / `apply_ack_recv` | Apply acknowledgements |
| `grid.replication.connect_failures` | Peer connect failures; first signal for network trouble |
| `grid.replication.ship_backpressure` | Shipping throttled against `flow.max-inflight-ops` |
| `grid.replication.swarm_hint` | Latest placement hint, as an ordinal |
| `grid.replication.overlay_pinned_keys` | Live placement pins — [overlay PIN](configuration/overlay-pin.md) |

Storage and reads:

| Metric | Use |
|--------|-----|
| `grid.replication.map_hit_rate` | Working-set hit rate |
| `grid.replication.sealed_misses`, `grid.sealed.miss` | Reads served from sealed files |
| `grid.sealed.window_remap` | Mapping-window churn on large payloads |
| `grid.sealed.index_hit` / `index_miss` | Sealed secondary index effectiveness |

SQL and locking:

| Metric | Use |
|--------|-----|
| `grid.sql.executions` | Statement rate |
| `grid.sql.tx_commits` / `tx_rollbacks` | Transaction outcome mix |
| `grid.sql.session_opens` | Logical session churn |
| `grid.sql.lock.wait_acquires`, `wait_timeouts`, `wait_cancels`, `wait_nanos` | Record-lock contention |
| `grid.sql.cancel.requests` / `cancel.active` | Client cancellations |
| `grid.sql.distributed.fan_in_calls` / `fan_in_sources` / `fan_in_rows` | Distributed read fan-in |

## Diagnosing write latency

Write latency splits cleanly into a consensus stage and a disk stage. Read both before changing configuration:

| Observation | Interpretation | Next step |
|-------------|----------------|-----------|
| `orchid_wait_p99_ns` rises, fsync flat | Peer network, phase threshold, or digest load | Check `connect_failures`, peer round-trip time, `orchid_r` |
| `oplog_fsync_p99_ns` rises, consensus wait flat | Disk service time or segment rotation | Check device latency, `op-log.segment-size`, competing I/O |
| Both rise together | Host-level saturation | Treat the measurement as invalid for comparison; reduce contention and repeat |
| `ship_backpressure` rises | Shipping cannot keep up with commits | Check peer apply rate and `flow.max-inflight-ops` |
| `map_hit_rate` falls while sealed misses rise | Working set too small for the traffic | Raise `working-set-max-entries` if heap allows — [durability](configuration/durability.md) |

Stage-by-stage breakdown of the write path: [consensus write path](../performance/perf-bio-consensus.md).

### Duty guidance

- Readiness UP is required before traffic; DOWN at start with replication until ORCHID syncs is expected.
- After promote: `writerEligible: true` on the new Active and client meta match — otherwise write traffic hits the old node.
- Replica reads: if `applyLagStale: true`, do not “fix” it in the client — catch up first.
- Rising `rpo_estimate_ms` across sites with no known incident — check the network and `ASYNC` / `SYNC` mode.

## Alerting

Build alerts on the fields above rather than on invented thresholds.

| Condition | Why it matters | Severity |
|-----------|----------------|----------|
| Readiness DOWN after the expected warm-up window | The node must not receive traffic | Page |
| No node reports `writerEligible: true` | No write admission anywhere | Page |
| Two nodes report `writerEligible: true` in different sites | Split-brain risk | Page; see [multi-site](operations/multi-dc.md) |
| `orchid_r` below `orchid.order-threshold` for a sustained period | Writes will be rejected | Page |
| `applyLagStale: true` on a node serving reads | Reads are being rejected, or stale | Ticket, then investigate lag |
| `repair_issued` climbing with flat `repair_applied` | Catch-up is stuck | Ticket |
| `oplog_fsync_p99_ns` above your disk budget, or disk near full | Durability path stalling | Page |
| `rpo_estimate_ms` climbing with no known network event | Growing cross-site loss window | Ticket |
| `lock.wait_timeouts` rising | Transaction contention reaching clients as errors | Ticket |

## Typical signals

| Signal | Meaning | First steps |
|--------|---------|-------------|
| Readiness DOWN | SQL not listening yet, or ORCHID not synced | Check probe details (`orchidSynced`), start logs; do not send traffic |
| `orchid_r` below threshold | No write admission | Check peer network, `peers` list, digest load — [replication](configuration/replication.md) |
| `applyLagStale: true` | Replica too far behind for reads | Do not read from it; catch-up / repair; threshold `ha.max-stale-lag` |
| Rising OpLog lag | Catch-up between nodes cannot keep up | Network, Applier disk, write load; seq on both. `/replication/compare` is lab-only, not day-to-day HA |
| `writerEligible: false` after promote | Client still on the old writer | Wait for `PROMOTE_NOTIFY` or `rediscoverWriter()` — [promote](operations/ha-promote.md) |
| Reject on `regionEpoch` | Client on a stale site epoch | `rediscoverWriter()`, do not rotate the next URL host |
| `repair_issued` rising, `repair_applied` flat | Catch-up is not applying | HomologousRepair logs, disk, seq mismatch |

## Verification after a writer hand-off

1. On the intended writer, readiness is UP and `writerEligible: true`.
2. No other node in the same site reports `writerEligible: true`.
3. The application saw `PROMOTE_NOTIFY`, or re-read the same metadata during reconnect. Health endpoints are not part of this path.
4. Replicas show `applyLagStale: false` before you route reads to them.
5. One write and one read succeed through the application path.

Procedure: [role promotion](operations/ha-promote.md). Topologies: [single-site HA](operations/cluster-ha-highload.md), [multi-site](operations/multi-dc.md).

## Surfaces and their scope

| Surface | Scope |
|---------|-------|
| Wire `ServerMeta` and `PROMOTE_NOTIFY` | The only source a client uses to pin a writer |
| Actuator health and Micrometer | Orchestrator probes, dashboards, alerts |
| `GET /replication/compare` | Lag comparison between two nodes on a test stand. Not writer discovery, not an operational consistency check |

Incident procedures: [failures](operations/failures.md).

## Capacity planning

Lab-host TPS figures: [capacity](../performance/capacity-slo.md). How to drive load with JMeter: [load and SLO](../tools/jmeter-load-slo.md). On duty, lean on readiness and the signals above — not on TPS numbers.

**Related:** [failures](operations/failures.md), [role promotion](operations/ha-promote.md), [replication](configuration/replication.md), [durability](configuration/durability.md).
