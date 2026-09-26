# Replication network path

Peers must receive the same journal the writer confirmed. Exchange is over Netty (`NettyReplicationTransport`) — there is no second transport inside a site or across sites. The journal and ORCHID state live on disk under `op-log.data-dir`.

In the demo, replication listens on **5615** / **5616**, SQL on **15432** / **15433**.

**Do not send SQL to 5615/5616.** Those ports carry replication frames; an SQL client gets `bad frameLen`. Incidents: [failures](../configure-and-operate/operations/failures.md).

## How a change reaches other nodes

```
ORCHID admission (phase + checksum)
      │
OpLog: append + confirmation
      │
put into the map (the row becomes visible)
      │
ship to peers over Netty
      │
apply on peer → peer map
```

*Figure 1. Shipping to peers is the last step, not the first.*

Two similar-sounding mechanisms should be kept apart:

| Mechanism | What it does |
|-----------|--------------|
| Replication | Ships journal operations to peers: `UPSERT`, `DELETE`, transaction markers |
| Shard map-reduce | Spreads **shard-local keys** across threads of one node for heavy SELECTs |

The second is not SQL fan-out to peers. The old “same SQL to every node” mode is disabled.

## Wire frames

```
uint32 BE length | uint8 opcode | body
```

| Opcode family | Purpose |
|---------------|---------|
| `HELLO` | Introduction; a peer with the same `clusterId` is added to the peer list |
| `OPLOG_PUSH` / `OPLOG_PULL` | Journal shipping and catch-up ranges |
| `APPLY_ACK` / `APPLY_NACK` | Peer confirmation of applied sequence numbers |
| `ORCHID_*` | Phase exchange, proposals, commit broadcast |
| `REPAIR_*` | Locus reconcile, range reship, single-row fetch |

This is a private binary protocol between nodes. It is not exposed to applications, it is not the SQL wire, and there is no HTTP replication API to poll.

## Inside one data centre

Shipping happens **asynchronously** after the write has been confirmed on the writer. Visibility on a replica therefore lags, which is what the `eventual_replica` read mode and the `maxApplyLag` metric describe.

| Layer | Guarantee |
|-------|-----------|
| Writer (phase-ranked proposer) | ORCHID plus OpLog **before** map visibility, so a session reads its own writes |
| Local peer (replica) | Asynchronous ship then apply; read mode `eventual_replica` |
| RPO | Non-zero while operations are in flight or not yet applied: sub-millisecond to tens of milliseconds on a calm host, larger under load or peer lag |
| Empty replica at boot | Expected until catch-up completes; bootstrap data loading runs on the primary profile only |

Lag is the expected price of this design, not an apply defect. An application that reads from a replica must be ready for it — including a refusal once staleness passes `maxStaleLag`: [replica reads](../configure-and-operate/operations/replica-reads.md).

**Not a bug:** a short window where a just-written key is missing on a replica.
**A bug:** a durable mismatch after lag has settled to zero and repair has run — a checksum difference or a missing key at equal sequence watermarks.

## Applying on a peer

`ReplicaApplier` buffers `UPSERT` and `DELETE` between `TX_BEGIN` and `TX_COMMIT` and applies them in one piece, so a reader on the replica never sees half a transaction. The peer writes its own OpLog while applying — a replica is a durable copy, not a cache of the writer.

Gaps and divergence are closed by `HomologousRepair`: it reconciles checksums through the change-locus journal and, when needed, asks for a range reship or a single row. Shard ownership itself may move between nodes; that is `AdaptiveReplicaSwarm` and `ShardMigrator`, described in [shard placement](overlay-and-swarm.md).

## Between data centres

`CrossDcPublisher` handles shipping to remote sites. There are two modes:

| Mode | Behaviour |
|------|-----------|
| `ASYNC_SHIP` | Changes go to remote data centres asynchronously. The local commit does not wait for them |
| `SYNC_VOTERS_ACROSS_DC` | The listed remote nodes become digest voters: the commit waits for their confirmation, with a timeout. Catch-up-only nodes (`cross-dc.learners`) stay asynchronous |

In the second mode remote nodes take part **only** in digest agreement. Their phases do not enter the local order parameter `R` unless WAN phase coupling is explicitly enabled, and it is off by default.

The trade is straightforward: `ASYNC_SHIP` keeps commit latency local and accepts a cross-site RPO; `SYNC_VOTERS_ACROSS_DC` pays WAN round-trip on every write to shrink it. Deployment and mode choice: [multi-site](../configure-and-operate/operations/multi-dc.md).

## Configuration

```yaml
grid.replication:
  orchid:
    coupling: 15.0
    natural-freq-hz: 1.0
    order-threshold: 0.85
    tick-ms: 10
    digest-quorum: MAJORITY
  repair:
    homologous-enabled: true
    reconcile-interval-ms: 5000
  swarm:
    enabled: true
    score-window-ms: 5000
    migrate-threshold: 0.3
  placement-optimizer:
    enabled: true
  transport:
    bind-host: 127.0.0.1
    bind-port: 5615
    peers:
      - { id: replica-1, host: 127.0.0.1, port: 5616, dc: dc-a }
  cross-dc:
    enabled: true
    local-dc: dc-a
    mode: ASYNC_SHIP        # or SYNC_VOTERS_ACROSS_DC
    phase-coupling: false   # off: remote phases stay out of the local R
    remote-ack-timeout-ms: 5000
    voters: []              # synchronous digest voters under SYNC_VOTERS_ACROSS_DC
    learners: []            # asynchronous catch-up only
  op-log:
    data-dir: ./data/replication
    fsync: true
```

Keep `2 * pi * natural-freq-hz * tick-ms / 1000 ≪ 1` — see [ORCHID](orchid-consensus.md) for why.

There are **no** `hdcrm.*` configuration keys. Placement knobs live under `swarm` and `placement-optimizer`. Full parameter reference: [replication](../configure-and-operate/configuration/replication.md).

## Observability

Replication exposes itself through health details and metrics, not through a status endpoint:

- Actuator `/health/liveness` and `/health/readiness` details: `orchidR`, `repairIssued` / `repairApplied`, `rpoEstimateMs`, `swarmHint`, `writerEligible`.
- Micrometer gauges: `grid.replication.orchid_r`, `repair_*`, `rpo_estimate_ms`, `swarm_hint`.
- `ReplicationMetrics`: push and ack counters, `orchidWaitP50/P99Ns`, `oplogFsyncP50/P99Ns`, duplex counters.

Comparing locus digests between nodes is an operator action over SQL TCP and admin tooling, not REST discovery. Clients never discover the writer over HTTP: they use `ServerMeta` and `PROMOTE_NOTIFY`. Dashboards and thresholds: [monitoring](../configure-and-operate/monitoring.md).

## What failure looks like

| Situation | What happens |
|-----------|--------------|
| Writer unreachable | The client rediscovers an eligible writer via `PROMOTE_NOTIFY` / AUTH — [promote a node](../configure-and-operate/operations/ha-promote.md) |
| Replica lagging | `maxApplyLag` grows; a replica SELECT may be refused past `maxStaleLag` — [replica reads](../configure-and-operate/operations/replica-reads.md) |
| Peer down, then back | Catch-up ships the missing range; a full journal resend is not required |
| Hole in the OpLog | `HomologousRepair` and sparse catch-up ship a range, not the whole log |
| Witness in multi-site | Takes part in region fencing; does not hold the Active writer role |
| SQL sent to 5615 | `bad frameLen`: that is the replication port, not SQL TCP |

Next: [ORCHID](orchid-consensus.md), [replication state](replication-state.md), [monitoring](../configure-and-operate/monitoring.md).
