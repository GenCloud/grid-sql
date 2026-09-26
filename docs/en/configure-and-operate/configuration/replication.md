# Replication

Two nodes, one journal. How does the second learn about a commit, and when may the cluster accept a write at all? Replication ships committed journal records to peers, admits writes only under ORCHID agreement (phase + checksum), and closes gaps on catch-up.

That is **not** the same as durability on disk: peers turn on with `grid.replication.enabled`, while [durability](durability.md) works with none.

Node-to-node transport is always Netty on its own port. Every node keeps its own data directory (not a shared NFS for the cluster).

## Minimal highly available pair

| Node | Profile | SQL port | Replication port |
|------|---------|---------:|-----------------:|
| primary-1 | `primary` | 15432 | 5615 |
| replica-1 | `replica` | 15433 | 5616 |

```yaml
grid:
  replication:
    enabled: true
    node-id: primary-1
    cluster-id: example-grid
    orchid:
      order-threshold: 0.85
      digest-quorum: MAJORITY
    transport:
      bind-host: 127.0.0.1
      bind-port: 5615
      peers:
        - { id: replica-1, host: 127.0.0.1, port: 5616, dc: dc-a }
    ha:
      max-stale-lag: 10000
      replica-reads-enabled: true
    op-log:
      fsync: true
```

Replace `bind-host: 127.0.0.1` with the interface that peers can reach; a loopback bind only works when both nodes share a host.

## Settings that must be correct before the first write

| Setting | Default | Consequence if wrong |
|---------|---------|----------------------|
| `enabled` | `false` | Peers never start; the node runs solo |
| `node-id` | `node-1` | Duplicate identifiers break HELLO and quorum counting |
| `cluster-id` | `grid-default` | A peer with a different cluster id is rejected |
| `transport.bind-host` / `bind-port` | `0.0.0.0` / `5615` | No inbound peer channel |
| `transport.peers` | `[]` | With an expected size above one, the node forms no quorum and readiness stays down |
| `orchid.order-threshold` | `0.85` | Write admission becomes either too permissive or permanently blocked |
| `orchid.digest-quorum` | `MAJORITY` | Quorum is computed over the configured same-site voters |
| `op-log.fsync` | `true` | Acknowledged commits can disappear after a host crash |

Solo writes are allowed only at bootstrap with an empty peer list. After a partition the node does not fall back to solo, and `forgetPeer` does not shrink the configured quorum — it is not a way to keep writing with half a cluster.

## ORCHID and transport tuning

| Setting | Default | Notes |
|---------|---------|-------|
| `orchid.coupling` | `15.0` | Phase coupling strength; leave at the default unless a measured run justifies a change |
| `orchid.natural-freq-hz` | `1.0` | Keep `2·π·freq·tick/1000` well below `1` |
| `orchid.tick-ms` | `10` | Sync tick period; smaller values raise CPU cost |
| `orchid.max-propose-in-flight` | `64` | Concurrent proposals; raise only when admission, not disk, is the bottleneck |
| `transport.connect-timeout-ms` | `5000` | Peer connect timeout |
| `transport.max-frame-bytes` | `16777216` | 16 MiB frame ceiling for replication frames |
| `flow.max-inflight-ops` | `10000` | Shipping backpressure ceiling; watch `grid.replication.ship_backpressure` |
| `repair.homologous-enabled` | `true` | Background gap repair |
| `repair.reconcile-interval-ms` | `5000` | Reconcile period |
| `ha.max-stale-lag` | `10000` | Apply-lag ceiling in journal ops for replica reads; `Long.MAX_VALUE` disables the gate |
| `ha.replica-reads-enabled` | `false` | Off in the library; starter `primary` and `replica` profiles turn it on |

Placement (`swarm.*`, `placement-optimizer.*`) is enabled by default and moves shard ownership on its own; `swarm.apply-auto-cutover` defaults to `true`. If you need placement to hold still during an investigation or a peak window, use [overlay PIN](overlay-pin.md) rather than disabling the subsystem.

## Bring-up procedure

1. Give every node a distinct `node-id` and the same `cluster-id`.
2. Point each node's `transport.peers` at the other nodes' replication ports — never at their SQL ports.
3. Start the nodes and wait for readiness. With replication on, readiness stays DOWN until ORCHID is synced; that is expected and is not a broken SQL port.
4. Confirm `orchidSynced: true` and `orchidR` above `order-threshold` in the readiness details.
5. Confirm exactly one node reports `writerEligible: true`.
6. Run one write and one read through the application path, then check apply lag on the replicas.

Clients do not choose the writer from configuration: they take it from wire metadata (`ServerMeta`, `PROMOTE_NOTIFY`) — see [role promotion](../operations/ha-promote.md).

## Deciding what to enable later

| Need | Where |
|------|-------|
| `SELECT` served from replicas | [replica reads](../operations/replica-reads.md) |
| Stable shard placement for specific keys | [overlay PIN](overlay-pin.md) |
| A second site | [multi-site](../operations/multi-dc.md) |
| Sizing under sustained load | [single-site HA](../operations/cluster-ha-highload.md), [multi-site](../operations/cluster-multidc-highload.md) |

## Cross-site modes

`grid.replication.cross-dc.mode` selects the write path across sites:

- `ASYNC_SHIP` — commit completes locally, the journal ships asynchronously. The cost is a recovery-point window at the standby site.
- `SYNC_VOTERS_ACROSS_DC` — commit waits for a remote digest acknowledgement. The cost is a wide-area round trip on every write.

Site fencing (`grid.replication.region.enabled`, default `false`) separates Active, Hold, and Witness roles and guards against two writers. Details: [multi-site](../operations/multi-dc.md), [multi-site under load](../operations/cluster-multidc-highload.md).

## Symptom and action

| Symptom | Cause | Action |
|---------|-------|--------|
| Readiness DOWN with `reason: orchid_not_synced` | Peers unreachable, or phase order still below threshold | Check peer connectivity and `grid.replication.connect_failures`; do not send load yet |
| Writes rejected with a not-synced error | Quorum unavailable | Restore peer connectivity or disk; never lower `fsync` or the threshold to push traffic through |
| `enabled: true` with an empty peer list on a multi-node cluster | Configuration mistake | Fill `transport.peers`; do not use `forgetPeer` to shrink the quorum |
| `bad frameLen` on a client connection | Client pointed at the replication port | Use the SQL port (15432 by default) |
| HELLO never converges | Mismatched `cluster-id`, duplicate `node-id`, or SQL port listed in `peers` | Fix identifiers and ports |
| Corrupt journal on two nodes | Two processes share one data directory | One data directory per process, local disk |
| `repair_issued` climbs while `repair_applied` does not | Peer disk, network, or a sequence mismatch | Inspect repair logs and peer sequence numbers — [replication state](../../understand/replication-state.md) |

## Constraints

- One data directory per node. Shared NFS or SAN paths corrupt journal and sealed files.
- Orchestrator probes use Actuator `/health/liveness` and `/health/readiness`. The HTTP lag-compare endpoint is a lab aid, not writer discovery.
- Declared durability behaviour assumes `op-log.fsync: true` on every node that may become a writer.

Next: [ORCHID](../../understand/orchid-consensus.md), [replication network](../../understand/replication-network.md), [monitoring](../monitoring.md).
