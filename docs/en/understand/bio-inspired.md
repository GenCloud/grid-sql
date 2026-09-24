# Appendix: biological metaphors and claim boundaries

Some Grid mechanisms have biological names: ORCHID and phase synchronization, “homologous” repair, “swarm” placement. These are **documentation** metaphors that help explain the idea. Public API and configuration use technical names: `orchid`, `HomologousRepair`, `AdaptiveReplicaSwarm`, `placement-optimizer`, `swarm`, `grid.overlay`.

What is implemented versus what remains a paper analogy. Use the table below when judging reliability claims.

## Claim boundaries

| Claim | What Grid actually does |
|-------|-------------------------|
| ORCHID | Crash / network-partition tolerance plus digest agreement. This is **not** paper Byzantine BFT: no 40% adversary model, no QSS |
| `HomologousRepair` | Heals lag and checksum divergence. It is **not** the HDCRM algorithm and **not** a second consensus layer |
| HDCRM | Documentation label for the hierarchical multipopulation **placement** optimizer (`placement-optimizer`, `swarm`). It is **not** `HomologousRepair` and does not replace ORCHID |
| Capacity planning numbers | Numbers in [capacity](../performance/capacity-slo.md). Do not lower them only to pass a check |

Paper BFT / QSS / OMNeT simulations are not imported into product runtime or configuration.

## ORCHID: phase synchronization

Phase coupling follows Yoshiki Kuramoto’s coupled-oscillator model (the author of the model, not a Grid algorithm name). In `OrchidNode`:

1. Peers exchange `ORCHID_PHASE`: phase, ω, last committed seq, optional propose digest.
2. Order parameter `R` measures sync among **seen live** peers, by default inside the local data center. Writes need `R ≥ order-threshold`.
3. Solo write is allowed only when configured `N = 1` (empty peer list) — not after partition or `forgetPeer`.
4. The proposer is phase-ranked (`min(nodeId)` among self and seen); competing proposes get NACK.
5. `ORCHID_PROPOSE` carries the previous op seq. Majority on one digest commits. Journal confirm happens before commit broadcast.
6. Last committed seq is stored under the node’s `orchid/` directory.

Failover is re-sync of phases plus digest quorum — not leader election.

Invariants and formal model: [ORCHID](orchid-consensus.md), [orchid-tla](../internal/orchid-tla.md).

### Local `R` vs multi-DC

| Mechanism | Default | Role |
|-----------|---------|------|
| Phase order `R` | Peers in the local data center | Whether a write may be proposed at all |
| Digest agreement | Configured local-DC peers plus self | Agreement on a concrete operation |
| Cross-DC voters | `SYNC_VOTERS_ACROSS_DC` plus `cross-dc.voters` | Wait for remote digest ACK (with timeout) |
| WAN phase-coupling | Off (`cross-dc.phase-coupling`) | Only when enabled do remote phases enter `R` |

Commit invariant: local sync **and** local digest quorum **and** (when configured) remote voter digests.

We do **not** claim: LAN-style phase coupling over WAN RTT; phase replacing digest quorum; placement optimizer as consensus.

## Homologous repair

`VersionLocusMap` maps `(domain, shard, keyHash)` to `{opSeq, schemaEpoch, checksum}`. The map is stored under `locus/`.

Reconcile can coalesce lag into a reship range, and on same-seq checksum mismatch request a concrete row.

Repair heals lag and corruption. It does not elect a leader and is not a second consensus layer.

Defaults: `homologous-enabled: true` (wire repair plus locus observe on the commit path), `reconcile-interval-ms: 5000`.

## Swarm placement and optimizer

`AdaptiveReplicaSwarm` collects local sensors — apply lag, queue depth, heap pressure, working-set hit rate, RTT — and emits a placement hint above a threshold. Hints bias ship urgency and catch-up preference; they do **not** block OpLog catch-up on join.

Why placement is separate from ORCHID:

| Task | What we choose | Cost of a bad choice |
|------|----------------|----------------------|
| Shard / replica placement | which shard on which node / DC | hot-spot, extra RTT, OOM, long catch-up |
| Cross-DC voter set | who ACKs digest synchronously | WAN on every write vs RPO risk |
| Repair priority | which divergence to heal first | long inconsistency window |
| Ship urgency / batching | when to flush journal segments | lag vs CPU/network |

ORCHID answers “may we write / who proposes?”. Placement is combinatorial and solved separately.

Current shape: subpopulations are DC layouts and node/shard layouts inside a DC (coarse→fine). Fitness uses existing sensors. Diversity / migration operators avoid collapse onto one “free” node. Output is a hint or migrate plan executed by `ShardMigrator` via OpLog and sealed packs.

Config names are only `swarm` and `placement-optimizer`. There is no `hdcrm.*` public YAML or API.

Out of current scope: general hyperparameter tuning and replacing ORCHID.

Operator view: [overlay and swarm](overlay-and-swarm.md), [replication](../configure-and-operate/configuration/replication.md).

### Ownership transfer (`ShardMigrator`)

Shard-move flush lifecycle: **stop accepting (`QUIESCE`) → catch-up (`CATCH_UP`)** (sealed pack + OpLog range) **→ ownership change → done (`CUTOVER_DONE`)**.

Safety contract:

1. Ownership changes only after entering `CATCH_UP` (sealed pack + OpLog range push attempted).
2. Ownership transfer goes through Netty OpLog ship — the same durable stream as normal replication (confirm-before-visible), not a silent map rewrite. When a sealed root is bound, `CATCH_UP` also ships shard artifacts (`.gmap` / `.sbpt` / `.sbm`).
3. Affinity pins on `ShardPlacementMap` override one-off peer targets.
4. An empty catch-up range may still cut over (already caught up); a non-empty range pushes a checksummed OpLog segment then cuts over.
5. Join / SparseCatchUp always push the OpLog tail — swarm `KEEP` must not skip catch-up forever.
6. Overlay PIN: live pins skip migrate of **that** shard; open SQL TX pressure may suppress the whole tick.

### Auto ownership transfer (`apply-auto-cutover`)

Default `grid.replication.swarm.apply-auto-cutover: true`: a placement plan actually calls `ShardMigrator.migrateRange`. When `false`, the tick still scores sensors and hints (including voter-set) but does not migrate. Use `false` only as a temporary suppress under load.

What to verify on the target topology:

1. QUIESCE → CATCH_UP → ownership change without OpLog checksum mismatch; ownership only via journal ship; an incomplete TX holds `CATCH_UP` without ownership leak.
2. PIN on a key blocks migrate of **its** shard; other shards are not frozen by one pin.
3. An open SQL TX may suppress the whole swarm tick; PIN stays shard-scoped.
4. A durable PIN survives restart until UNPIN/TTL — [overlay-pin](../configure-and-operate/configuration/overlay-pin.md).
5. Voter-set hints do not create two writers together with client pin (`ServerMeta` / `PROMOTE_NOTIFY`).

Capacity numbers on the measurement host: [capacity](../performance/capacity-slo.md).

### Placement optimizer (YAML)

```yaml
grid.replication.placement-optimizer:
  voter-set-hints-enabled: true
  target-remote-voters: 1
  apply-voter-set-hints: true   # default on; false = hints and metrics only
```

When `apply-voter-set-hints: true`, the swarm tick applies the recommendation via `OrchidNode.applyRemoteVoters` — **remote digest voters only** (local `R` unchanged). `PeerEndpoint` carries `PeerRole` (`VOTER` | `LEARNER`); remote peers are tagged from `OrchidMultiDcConfig.remoteVoterIds`.

v1 shape: DC → node/shard subpopulations; fitness from sensors (lag, RTT, heap, hitRate); output `PlacementHint` / plan → `ShardMigrator`. Out of v1: general hyperparameter tuning and replacing ORCHID.

## Epigenetic overlays

`grid.overlay.enabled` (and optional durable store) provides soft TTL / pin / QoS sidecars under `overlay/`. Not a second row store and not a change to row data.

Practical use: [overlay and swarm](overlay-and-swarm.md). Configuration: [overlay and PIN](../configure-and-operate/configuration/overlay-pin.md).

## Sealed dump / index contract

See [storage](storage-sealed-gmap.md). Domain dump is `SealedGridMapService.dumpDomain`. `SnapshotService` is OpLog-range hydrate and markers only. `IndexCheckpointService` meta after rebuild is a **KEYS watermark** (plus optional CRC), not a full RAM BPTree restore; durable secondary index remains sealed `.sbpt`.

## Cross-DC

`ASYNC_SHIP` — async OpLog ship to remote-DC peers.  
`SYNC_VOTERS_ACROSS_DC` — remote voters ACK digest before commit; catch-up-only nodes (`cross-dc.learners`) stay async. Local phase order `R` stays inside the DC by default (`cross-dc.phase-coupling` off).

Node maps: [multi-DC](../configure-and-operate/operations/multi-dc.md).

## What is not in the system

- CRDT-style merge of competing masters (“alleles”). Consensus is ORCHID plus the journal; peers catch up and repair.
- Biological names in the public API or configuration.
- An `hdcrm.*` config namespace.
- WAN phase-coupling as the default multi-DC consistency path.

## Related

[ORCHID](orchid-consensus.md), [replication state](replication-state.md), [replication network](replication-network.md), [architecture overview](architecture-overview.md).

Measurements: [capacity and SLO](../performance/capacity-slo.md), [results](../performance/results.md), [write critical path](../performance/perf-bio-consensus.md), [methodology](../performance/methodology.md).
