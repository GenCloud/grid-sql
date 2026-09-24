# Multi-DC + high-load

Cross-DC replication builds on same-DC ORCHID. Local phase order `R` stays in the local DC by default. Across the WAN you choose either async ship or remote digest voters; phase-coupling across sites is off unless you enable it.

Multi-DC write admission uses **Active / Hold / Witness** region roles plus a monotonic **`regionEpoch`** fence (see [ha-promote.md](ha-promote.md)). Only the Active region admits client SQL writes; Hold may claim after Active silence + quorum; Witness is off by default for claim majority.

Prerequisites: [cluster-ha-highload.md](cluster-ha-highload.md), [replication-network.md](../../understand/replication-network.md), [bio-inspired.md](../../understand/bio-inspired.md#cross-dc).

## Roles (Active / Hold / Witness)

| Role | Write admission | Typical placement |
|------|-----------------|-------------------|
| **Active** | Yes (with ORCHID `writerEligible`) | One DC at a time; owns current `regionEpoch` |
| **Hold** | No — may claim after `claim-timeout-ms` silence + quorum | Standby DC (warm OpLog / digest catch-up) |
| **Witness** | No — vote-only for claim quorum | Opt-in third site / tie-breaker (not required for ASYNC learners) |

`regionEpoch` advances on successful Hold claim. Clients pin on `writerEligible && regionEpoch`; mid-op fence → `rediscoverWriter()` (never silent rotate). Discovery is wire `ServerMeta` / `PROMOTE_NOTIFY`.

## Modes

| Mode | Commit path | Remote role | RPO / latency |
|------|-------------|-------------|----------------|
| **`ASYNC_SHIP`** | Local ORCHID + OpLog on **Active DC**; ship OpLog async to Hold / learners | Hold DC = catch-up (`write-admission` gated by region); SQL writes rejected on Hold/Witness; stale reads abort when lag / DC-link down | Non-zero RPO on Hold; low commit latency on Active; **not** dual-Active |
| **`SYNC_VOTERS_ACROSS_DC`** | Local ORCHID waits for remote **digest ACK** from voters before commit | `cross-dc.voters` (or remotes − `learners`) = sync; `learners` = async only | Lower RPO on voters; **WAN tax** on every write |

`cross-dc.phase-coupling` (default **false**): off by default — include remote phases in local `R`. Independent of digest voting. Prefer digest voters for multi-DC consistency.

`remote-ack-timeout-ms` (default `5000`): timeout for remote voter digests and optional apply-level ACK (`require-remote-ack`).

Witness is **off by default**: set `grid.replication.region.role: WITNESS` on a node that should vote in claim quorum without admitting writes. ASYNC_SHIP learners without region fencing remain write-admission=false catch-up replicas.

## Topology — Active + Hold clusters

Reference layout: **one Active DC** (write ring) and **one Hold DC** (warm standby). Role is per node via `grid.replication.region.role` (`ACTIVE` | `HOLD` | `WITNESS`). At bootstrap every node in a DC shares one role.

### Pseudo address plan (3+2)

| Node | DC | Region role | SQL (pseudo DNS) | SQL (host-published) | Replication |
|------|-----|-------------|------------------|----------------------|-------------|
| `a1` | `dc-a` | **ACTIVE** | `a1.dc-a.example:15432` | `127.0.0.1:15432` | `a1.dc-a.example:5615` |
| `a2` | `dc-a` | **ACTIVE** | `a2.dc-a.example:15433` | `127.0.0.1:15433` | `a2.dc-a.example:5616` |
| `a3` | `dc-a` | **ACTIVE** | `a3.dc-a.example:15434` | `127.0.0.1:15434` | `a3.dc-a.example:5617` |
| `b1` | `dc-b` | **HOLD** | `b1.dc-b.example:15435` | `127.0.0.1:15435` | `b1.dc-b.example:5618` |
| `b2` | `dc-b` | **HOLD** | `b2.dc-b.example:15436` | `127.0.0.1:15436` | `b2.dc-b.example:5619` |

Optional Witness: e.g. `w1.dc-w.example:15437` / repl `5620`, `region.role: WITNESS`, no OpLog hydrate payload. See Witness diagram in [promote a node](ha-promote.md).

### ASYNC_SHIP node map

```mermaid
flowchart LR
  subgraph dcA [DC_A_Active]
    A1["a1_writer"]
    A2["a2"]
    A3["a3"]
  end
  subgraph dcB [DC_B_Hold]
    B1["b1_Hold"]
    B2["b2_Hold"]
  end
  Client[Client] -->|SQL_Active_only| A1
  A1 <-->|Netty_same_DC| A2
  A1 <-->|Netty_same_DC| A3
  A1 -.->|journal_async_WAN| B1
  A1 -.->|journal_async_WAN| B2
```

Commit stays local on Active; Hold catches up. Hold RPO is non-zero.

### SYNC_VOTERS node map

```mermaid
flowchart LR
  subgraph dcA2 [DC_A_Active]
    S1["a1_writer"]
    S2["a2"]
    S3["a3"]
  end
  subgraph dcB2 [DC_B_voters]
    V1["b1_voter"]
    L1["b2_learner_async"]
  end
  Client2[Client] -->|SQL_Active| S1
  S1 <-->|Netty_same_DC| S2
  S1 <-->|Netty_same_DC| S3
  S1 ==>|digest_ACK_before_commit| V1
  S1 -.->|async_only| L1
```

Commit waits for remote voter digests (`remote-ack-timeout-ms`). Learners stay async and never serve client SQL.

Bootstrap YAML (same `epoch` on every node until a claim advances it):

```yaml
# on a1 / a2 / a3
grid.replication.region:
  enabled: true
  role: ACTIVE
  epoch: 1
  claim-timeout-ms: 5000
  quorum-size: 2

# on b1 / b2
grid.replication.region:
  enabled: true
  role: HOLD
  epoch: 1
  claim-timeout-ms: 5000
  quorum-size: 2
```

Hold nodes also keep `cross-dc.write-admission: false` while Hold. After a successful claim, the new Active DC owns `regionEpoch+1`; revived former Active fences to Hold on HELLO / meta (never auto-Active “because it woke up”).

### Client connection strings

Linearizable writes → pin on `writerEligible && regionEpoch` (wire `ServerMeta` / `PROMOTE_NOTIFY`). Mid-op fence → `rediscoverWriter()` — never silent rotate.

| Use case | Connection string |
|----------|-------------------|
| **Product write (region fencing on)** — dual-DC authority | `grid://app:secret@a1.dc-a.example:15432,a2.dc-a.example:15433,a3.dc-a.example:15434,b1.dc-b.example:15435,b2.dc-b.example:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2` |
| **Host-published write** (Jepsen / laptop publish) | `grid://app:secret@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2` |
| Active DC ring only (same-DC failover / fencing off) | `grid://app:secret@a1.dc-a.example:15432,a2.dc-a.example:15433,a3.dc-a.example:15434/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2` |
| Hold read-only (accept abort-on-stale) | `grid://app:secret@b1.dc-b.example:15435,b2.dc-b.example:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2` |

```
# Reference application write URL — Active + Hold peers (region fencing ON)
grid://app:secret@a1.dc-a.example:15432,a2.dc-a.example:15433,a3.dc-a.example:15434,b1.dc-b.example:15435,b2.dc-b.example:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2

# Same topology, host-published ports (compose / Jepsen)
grid://app:secret@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2
```

After `a1` crash inside Active DC → pin / `promoteHint` → `a2`/`a3` (same `regionEpoch`). After whole Active DC loss → Hold claim advances `regionEpoch`; client `rediscoverWriter()` pins the new Active in DC-B — do not silent-rotate mid-op (see [promote a node](ha-promote.md)).

```mermaid
sequenceDiagram
  participant A as Active_DC_A
  participant H as Hold_DC_B
  participant W as Witness_opt
  participant C as Client
  A--xH: DC_A_down
  H->>W: claim_vote
  H->>H: quorum_ok regionEpoch_plus_1
  H->>C: PROMOTE_NOTIFY_new_epoch
  C->>C: rediscoverWriter
  C->>H: SQL_on_new_Active
  Note over A,H: "revived former Active becomes Hold not Active"
```

## CrossDcPublisher

```mermaid
sequenceDiagram
  participant M as MutationRecorder
  participant O as OrchidNode
  participant X as CrossDcPublisher
  participant V as Remote_voter
  participant L as Remote_Hold_learner
  Note over M,O: "Local R and digest - SYNC waits remote voter digests"
  M->>O: appendAndWaitCommit
  O-->>M: committed
  M->>X: ship complete TX units only
  alt ASYNC_SHIP
    X-->>V: OPLOG_PUSH async
    X-->>L: OPLOG_PUSH async
  else SYNC_VOTERS_ACROSS_DC
    X-->>V: OPLOG_PUSH to voters
    X-->>L: async learners only
  end
  Note over X: "TxEnvelopeCoordinator - no mid-unit dirty ship"
```

`CrossDcPublisher` batches (`batch-max-ops` / `batch-max-wait-ms`), buffers open TX until `TX_COMMIT`/`TX_ABORT`, and holds multi-shard envelopes until all shards commit. Transport is always Netty ([replication-network.md](../../understand/replication-network.md)).

## RPO vs WAN tax

```mermaid
flowchart TB
  subgraph asyncMode [ASYNC_SHIP]
    W1[Write commit Active] --> S1[Ship WAN async]
    S1 --> R1[Hold apply lag = RPO]
  end
  subgraph syncMode [SYNC_VOTERS_ACROSS_DC]
    W2[Write] --> D2[Wait remote digest ACK]
    D2 --> C2[Commit + ship]
    C2 --> R2[Voters closer to zero RPO]
    D2 -.->|RTT_timeout| Tax[WAN latency on critical path]
  end
```

| Concern | ASYNC_SHIP | SYNC_VOTERS_ACROSS_DC |
|---------|------------|------------------------|
| Commit p50/p99 | Dominated by local ORCHID + OpLog fsync | Local + remote digest RTT (`remote-ack-timeout-ms` ceiling) |
| Data loss window (remote DC) | Until ship+apply | Digests before commit on voters; learners still async |
| Partition DC-link | **Active DC** keeps writing; Hold rejects write + abort-on-stale read | Commits stall/fail when voters unreachable |
| Measured localhost tax | See [capacity](../../performance/capacity-slo.md) SYNC_VOTERS vs ASYNC | ~8× vs async on this host's JMH stand-in |

Same-DC peer RPO (async ship inside one DC) remains as in [ha-promote.md](ha-promote.md#same-dc-rpo-async-ship).

## YAML recipes

### Region fencing (`grid.replication.region`)

```yaml
grid:
  replication:
    region:
      enabled: true
      role: ACTIVE          # ACTIVE | HOLD | WITNESS
      epoch: 1              # bootstrap epoch (>= 1)
      claim-timeout-ms: 5000
      quorum-size: 2        # Hold/Witness ACK majority for claim
```

Hold / Witness nodes set `role: HOLD` or `role: WITNESS` and the same `epoch` bootstrap until a claim advances it. `enabled: false` keeps legacy primary-DC / `write-admission` behavior.

### ASYNC_SHIP (throughput / tolerate WAN RPO)

```yaml
grid:
  durability:
    enabled: true
    hydrate-mode: LAZY
    working-set-max-entries: 2_000_000
  sql:
    default-shards: 16
  sql-server:
    enabled: true
    port: 15432
  replication:
    enabled: true
    node-id: a1
    cluster-id: prod-multi
    orchid:
      coupling: 15.0
      natural-freq-hz: 1.0
      order-threshold: 0.85
      tick-ms: 10
      digest-quorum: MAJORITY
    transport:
      bind-port: 5615
      peers:
        - { id: a2, host: a2.dc-a, port: 5615, dc: dc-a }
        - { id: a3, host: a3.dc-a, port: 5615, dc: dc-a }
        - { id: b1, host: b1.dc-b, port: 5615, dc: dc-b }
        - { id: b2, host: b2.dc-b, port: 5615, dc: dc-b }
    region:
      enabled: true
      role: ACTIVE
      epoch: 1
      claim-timeout-ms: 5000
      quorum-size: 2
    cross-dc:
      enabled: true
      local-dc: dc-a
      mode: ASYNC_SHIP
      phase-coupling: false
      remote-ack-timeout-ms: 5000
      batch-max-ops: 64
      batch-max-wait-ms: 20
      require-remote-ack: false
      voters: []
      learners: [b1, b2]
      write-admission: true
    op-log:
      data-dir: ./data/a1/replication
      fsync: true
```

Hold nodes set `region.role: HOLD` and `write-admission: false` (never local write quorum while Hold).

### SYNC_VOTERS_ACROSS_DC (lower remote RPO / pay WAN)

```yaml
    cross-dc:
      enabled: true
      local-dc: dc-a
      mode: SYNC_VOTERS_ACROSS_DC
      phase-coupling: false
      remote-ack-timeout-ms: 5000
      batch-max-ops: 64
      batch-max-wait-ms: 20
      require-remote-ack: false
      voters: [b1]
      learners: [b2]
```

Empty `voters` with `SYNC_VOTERS_ACROSS_DC` → all remote peers except `learners`.

## Consistency checks

| Contour | Where |
|---------|--------|
| 1-DC Jepsen N=3 | [benchmarks/jepsen/README.md](../../../../benchmarks/jepsen/README.md) |
| Multi-DC Jepsen (ASYNC + SYNC_VOTERS, link cut, Active-site loss) | [benchmarks/jepsen/multidc/RESULTS.md](../../../../benchmarks/jepsen/multidc/RESULTS.md) |
| Formal companion | `spec/orchid/OrchidLogMultiDc` (not a substitute for Jepsen) |
| SYNC vs ASYNC write cost | [capacity](../../performance/capacity-slo.md), [ORCHID path](../../performance/perf-bio-consensus.md) |

Size WAN timeouts from measured RTT — not from localhost latency alone. Planning load numbers: [capacity](../../performance/capacity-slo.md). Consistency results: [results](../../performance/results.md) links to Jepsen. Do not co-run consistency checks with load — [methodology](../../performance/methodology.md).
