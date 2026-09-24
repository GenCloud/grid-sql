# Promote a node

No Raft election / term / vote.

## How the client learns the writer

SQL wire `AUTH_OK` carries `ServerMeta`; eligible-write ERROR responses carry the same metadata as a fresh hint. While a SQL channel is active, an ORCHID re-rank or region role/epoch change that updates `writerEligible`, `promoteHint`, `regionEpoch`, or `regionRole` also pushes a server-initiated `PROMOTE_NOTIFY` with the same payload. The client updates its pinned writer metadata immediately. AUTH and ERROR metadata remain the reconnect/poll fallback if a push races channel loss.

This is the application-client contract: the writer is chosen only from wire `ServerMeta` / `PROMOTE_NOTIFY`.
For orchestrator readiness use Actuator `/health/liveness` and `/health/readiness` plus Micrometer. Writer pin comes only from protocol meta, not from HTTP.

| Field | Meaning |
|-------|---------|
| `phaseRankedProposer` | Active writer id (`min(nodeId)` among synced) |
| `isPhaseRankedProposer` | This node may propose |
| `writerEligible` | Synced && phase-ranked && write-admission && region Active (when region fencing on) |
| `promoteHint` | Next eligible writer if current unavailable |
| `maxApplyLag` | Max OpLog vs applied lag |
| `readMode` | `read_your_writes` vs `eventual_replica` |
| `maxStaleLag` | Config threshold in journal ops: refuse stale reads when apply lag exceeds it |
| `schemaEpoch` | Duplex schema epoch (wire `ServerMeta`) |
| `regionEpoch` | Multi-DC Active/Hold fencing epoch (`0` = region disabled / legacy) |
| `regionRole` | Wire role: `NONE` / `ACTIVE` / `HOLD` / `WITNESS` |

## Same-DC RPO (async journal ship)

Peers in the same DC receive OpLog via async Netty push. After a primary write, replica map visibility can lag (`maxApplyLag` / missing key) until apply — that is **expected RPO**, not Applier failure. See [replication network](../../understand/replication-network.md). Watch lag in readiness (`applyLagStale`) and metrics; lab `GET /replication/compare` is not the writer pin source.

## Failover story

1. Phase-ranked proposer dies or partitions.
2. Survivors re-sync phase order `R`; new `min(nodeId)` among reachable synced nodes proposes.
3. Catch-up / HomologousRepair closes OpLog gaps.
4. Active clients receive `PROMOTE_NOTIFY`; reconnecting clients rediscover the same `ServerMeta.writerEligible` / `promoteHint` / `regionEpoch` via AUTH or ERROR — no manual Raft promote.

With `grid.replication.region.enabled=true`, a whole-DC Active loss can also advance `regionEpoch` when Hold completes a claim quorum — clients must re-pin on the new epoch.

## Client configuration (proposer crash)

The client must land on a node that **may write** (`writerEligible=true`), not on an arbitrary replica. Multi-host TCP failover alone is not full discovery.

### How pinning works

```mermaid
stateDiagram-v2
  [*] --> AuthOk: AUTH_OK_ServerMeta
  AuthOk --> Pinned: writerEligible_and_regionEpoch
  Pinned --> Updated: PROMOTE_NOTIFY
  Updated --> Pinned
  Pinned --> Rediscover: mid_op_fence_or_reject
  Rediscover --> AuthOk: rediscoverWriter
  Pinned --> AuthOk: channel_lost_reconnect
```

| Rule | Behavior |
|------|----------|
| Pin key | `writerEligible` and `regionEpoch` (`regionEpoch=0` → eligibility alone) |
| Live channel | `PROMOTE_NOTIFY` / AUTH / ERROR update pin metadata |
| Mid-op fence | Orchid reject or epoch mismatch → `rediscoverWriter()` — **not** silent rotate (dual-writer risk) |
| Source | Wire `ServerMeta` only |

### 1-DC cluster (all peers in one write ring)

```
grid://u:p@n1:15432,n2:15433,n3:15434/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2
```

| Layer | Behavior |
|-------|----------|
| `RemoteConnectionFactory` multi-host | Pinned TCP; on connect / channel death tries the next `host:port` |
| Application + Jepsen discovery | `PROMOTE_NOTIFY` updates active pinned writer metadata; AUTH / ERROR poll the same `ServerMeta` on reconnect |
| Orchid / stale / region fence | Do **not** rotate the pinned writer mid-history. Call `rediscoverWriter()` when the old pin loses `writerEligible` or `regionEpoch` mismatches |

`maxTxContexts` limits logical sessions on **one** TCP — not N sockets / Hikari-style pools.

### Multi-DC

| Mode | Write URL endpoints | Notes |
|------|---------------------|-------|
| Region fencing **off** (`ASYNC_SHIP` / `SYNC_VOTERS`) | Active (primary) DC peers only | Hold / learners / remote voters: omit from write URLs |
| Region fencing **on** | Dual-DC URL allowed | Pin still requires `writerEligible && regionEpoch`; Hold/Witness reject writes |

```
# Region fencing off — Active DC ring only
grid://app:secret@a1.dc-a.example:15432,a2.dc-a.example:15433,a3.dc-a.example:15434/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2

# Region fencing on — dual-DC Active+Hold (failover after Hold claim)
grid://app:secret@a1.dc-a.example:15432,a2.dc-a.example:15433,a3.dc-a.example:15434,b1.dc-b.example:15435,b2.dc-b.example:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2

# Host addresses (compose / Jepsen)
grid://app:secret@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435,127.0.0.1:15436/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2
```

Address inventory and YAML per DC: [cluster Multi-DC high load](cluster-multidc-highload.md).

Putting Hold / Witness / learner endpoints in the write URL without region fencing yields live TCP + `writerEligible=false` rejects. Optional learner / Hold read path is separate and must accept abort-on-stale (`maxStaleLag` / `applyLagStale`).

The client pins on `writerEligible` from protocol meta. Topologies and load: [cluster HA high load](cluster-ha-highload.md), [cluster Multi-DC high load](cluster-multidc-highload.md). Incidents: [failures](failures.md).

## Hold+Hold+Witness claim quorum (recipe)

Topology: DC-A Active (or down) + DC-B Hold + DC-C Hold + Witness voter.  
`grid.replication.region.quorumSize` must count Hold+Witness voters for majority.

```mermaid
flowchart TB
  subgraph dcA [DC_A_Active_or_down]
    A1["a_nodes"]
  end
  subgraph dcB [DC_B_Hold]
    B1["b_nodes"]
  end
  subgraph dcC [DC_C_Hold]
    C1["c_nodes"]
  end
  subgraph wit [Witness]
    W1["w1_vote_only"]
  end
  A1 -.->|silence| B1
  B1 -->|claim_vote| W1
  C1 -->|claim_vote| W1
  B1 -->|winner_new_Active| Client[Client_pin]
```

On Active loss: claim quorum (`RegionClaimQuorum`) picks a single winner among Hold (+ Witness); the client pins to the new `writerEligible` without dual-writer. Active / Hold / Witness roles live in node YAML when `grid.replication.region.enabled=true`. Compose and lab topologies: [Compose deploy](deploy-compose.md), [multi-site](multi-dc.md).

Incidents: [failures](failures.md).

## Related surfaces

Optional replica reads (off by default): [replica reads](replica-reads.md). Actuator readiness fields (`writerEligible`, `applyLagStale`, …): [monitoring](../monitoring.md).

**See also:** [multi-dc](multi-dc.md), [Java client](../../develop/java-client.md).
