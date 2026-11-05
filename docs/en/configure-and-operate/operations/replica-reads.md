# Replica reads

The client wants to offload SELECT from the writer. By default everything hits the writer. You can turn on routing: writes and TX stay on the writer; autocommit SELECT / EXPLAIN may go to replicas within lag policy. Better a reject on stale data than a silent old read.

## Topology (1 primary + N replicas)

```mermaid
flowchart TB
  App[Application]
  App -->|WRITE_TX_DDL_PREPARE_FOR_UPDATE| Writer["writer_ring\nPRIMARY"]
  App -->|SELECT_EXPLAIN| Reads["readEndpoints\nREAD_REPLICA"]
  Writer --> N1["n1_writer"]
  Writer --> N2["n2"]
  Reads --> R1["replica_1"]
  Reads --> R2["replica_N"]
  CatchUpOnly["catch_up_or_Witness"] -.->|no_client_SQL| X[reject]
```

Catch-up-only nodes and Witness never serve client SQL.

| Path | Where | Session role | Admission |
|------|-------|--------------|-----------|
| `begin`, DML, DDL, PREPARE, FOR UPDATE | writer pin ring | `PRIMARY` | `writerEligible` and region epoch and not stale |
| `createStatement` read-only SELECT/EXPLAIN | `readEndpoints` when present | `READ_REPLICA` | v2 ANTLR client route + server admission |
| `createReadStatement` / `executeRead` | `readEndpoints` | `READ_REPLICA` | explicit API (still supported) |

### FOR UPDATE and peer locks

`FOR UPDATE` / `SKIP LOCKED` always run on the **writer** (never a read replica). Indexed wire keys are locked locally via `LockAwareKeyCursor`. When replication is on and the coordinator has peers, product Boot wiring installs Netty `DistForUpdatePeerLockAgent`s from **replication `peers`** (`SqlServerRuntime` → `ReplicationCoordinator.createNettyDistForUpdatePeerLockAgents()`), so peers take the same locks through `DistForUpdateCoordinator`. Prepare votes carry a **per-peer key-set** (not a peer-only flag); abort waits for peer release (`releaseAwait`). Peer-held leases expire after **30s** TTL (sweeper; no sidecar prepare journal — OpLog TX is the durability path). A Netty error on a peer is a **reject to the client**, not a silent local-only commit. Autocommit releases statement peer leases in `finally`; open-TX leases stay until COMMIT/ROLLBACK. Prepare/commit-dec is product 2PC-lite for peer row locks — not XA. Multi-table / INNER JOIN `FOR UPDATE` is supported.

With replication off or an empty peer list, locks stay **local** on the writer only. There is no separate operational YAML key to list DistForUpdate endpoints. Do not confuse this with `grid.sql.distributed-peers` (read-only SELECT/JOIN fan-out) — [SQL server](../configuration/sql-server.md).

### v2 auto-route (recommended)

With `readEndpoints` configured, `ConnectionFactory.fromUrl(url)` returns a routing `Connection` that classifies SQL via shared ANTLR **`SqlRouteClassifier`**:

- **READ** (plain SELECT / EXPLAIN without locking) → least-inflight read endpoint (`ReadEndpointSelector`)
- **WRITE** / TX / DDL / PREPARE / `FOR UPDATE` → pinned writer

N `readEndpoints` are supported (`host:port` list). Prefer **`ConnectionFactory.fromUrl`** — routing is automatic; no manual dual-factory wiring.

To pin the current thread to the writer (Flyway / DDL tooling on a replica-capable URL), use `SqlClientRouteContext.forcePrimary()` / `runWithPrimary` / `callWithPrimary` — see [Java client](../../develop/java-client.md).

Server ANTLR admission (`SqlStatementTag`) remains the abort-on-error gate on `READ_REPLICA` sessions.

## Server configuration

### Enable replica reads (required)

```yaml
grid:
  durability:
    enabled: true
    hydrate-mode: LAZY
  replication:
    enabled: true
    ha:
      max-stale-lag: 10000
      replica-reads-enabled: true
```

Starter profiles (`application-primary.yml` / `application-replica.yml`) enable `replica-reads-enabled: true` for the local 1+1 demo. Library default remains **false**.

### Primary + one replica (local)

| Node | Profile | SQL | Repl | dataDir |
|------|---------|-----|------|---------|
| primary-1 | `primary` | `:15432` | `:5615` | `./data-primary/...` |
| replica-1 | `replica` | `:15433` | `:5616` | `./data-replica/...` |

Peers cross-reference under `grid.replication.transport.peers`. Per-node `dataDir` (never shared NFS).

## Client configuration

### Write URL (writer pin)

```
grid://user:pass@127.0.0.1:15432,127.0.0.1:15433/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2
```

Authority = failover ring for writer discovery (`PROMOTE_NOTIFY` / AUTH `ServerMeta`). Mid-op fence -> `rediscoverWriter()`.

### Read URL extras (N endpoints)

```
grid://user:pass@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433,127.0.0.1:15434&readPreference=REPLICA&maxReadConnections=6
```

| Query | Meaning |
|-------|---------|
| `readEndpoints` | Comma `host:port` read ring (N≥1 when `readPreference=REPLICA`); client merges authority hosts into the ring so **all listed synced voters** share least-inflight SELECT (skip stale / `applyLagStale`; no Orchid gossip — app must list SQL endpoints) |
| `readPreference` | `PRIMARY` (default) or `REPLICA` |
| `maxReadConnections` | TCP cap for read factory; size ≥ ring so least-inflight can land on every member |
| `staleReadPolicy` | v1: reject-on-stale only |

### Factories

```java
// Recommended: v2 auto-route via SqlRouteClassifier + RoutingConnection
ConnectionFactory factory = ConnectionFactory.fromUrl(url);
Connection conn = factory.obtain().block();
conn.createStatement("SELECT ...").execute();   // may hit replica
conn.createStatement("INSERT ...").execute();   // always writer

// Explicit read API still available
((RoutingConnection) conn).executeRead("SELECT ...");
RemoteConnectionFactory reads = RemoteConnectionFactory.createReadFactory(url);
```

`SESSION_OPEN` carries role (`SessionRoleWire` v2). `PROMOTE_NOTIFY` does **not** change session role.

## Stale / lag / completeness policy

Serving-stream `applyLagStale` alone is **not** the full read gate. Replica SQL admission also fails closed when local data is **incomplete**.

```mermaid
flowchart TB
  Req["READ_REPLICA statement"] --> A{"replicaReadsEnabled\nand not catch-up/Witness?"}
  A -->|no| DenyCfg["REPLICA_READ_DISABLED / deny"]
  A -->|yes| B{"applyLagStale\nor catalog hole?"}
  B -->|yes| Stale["REPLICA_READ_STALE / rotate"]
  B -->|no| C{"structural sealed hole\non local files?"}
  C -->|yes| Inc["incomplete / rotate"]
  C -->|no| D{"sticky missing-stream\nMissingStreamAdmitState?"}
  D -->|yes / index not ready| Inc
  D -->|no| Exec["execute → staging → map → sealed"]
```

*Figure 2. Admit before row I/O. Sticky missing-stream is O(1); disk inventory rebuild is off the SELECT path.*

### Dual-track: admit fence vs differential heal

| Track | Trigger | Effect on SELECT |
|-------|---------|------------------|
| **Admit fence** | `applyLagStale`, catalog fingerprint hole, **local** structural sealed hole, proposer **missing-stream** (sticky bit) | Deny / rotate / writer fallback |
| **Differential heal** | Peer HELLO inventory FP ≠ local index for `domain#shard` | Pull mismatched shards (`fromSeq=1`); **does not** fence admit by itself |
| **Aggregate root FP** | Single mixed fingerprint of all sealed packs | Metrics / HELLO advertise only — **never** an admit fence alone |

Missing-stream sticky state (`MissingStreamAdmitState` + `LocalSealedInventoryIndex`):

1. Seal / unpack / structural-hole invalidate → mark inventory **not ready** (fail-closed) and enqueue rebuild on `helloCatchUp`.
2. Rebuild walks disk once into the memory index and recomputes the sticky incomplete bit (`ProposerMissingStreamUtil`).
3. Every subsequent SELECT reads the sticky bit — **no** per-query `ofShardIdentity` directory walk.
4. While the index is not ready (post-invalidate, pre-rebuild), admit stays incomplete.

| Condition | Behavior |
|-----------|----------|
| `applyLagStale` (serving streams) | Reject `REPLICA_READ_STALE`; AUTH rotate on the read ring |
| Unhydrated LAZY tip debt / incomplete apply | Same client-visible incomplete signal — not a silent empty SELECT |
| `catalogFingerprint` mismatch vs peers | Deny replica read: catalog object-set incomplete (VIEW/TABLE hole) — message includes fingerprint mismatch |
| Sealed structural hole (magic-OK undersize / bad GMAP) on local files | Deny replica read: lag-complete ≠ sealed-complete. Aggregate root FP and peer inventory mismatch alone are **not** admit fences (inventory drives differential heal only). HELLO advertises per-shard identity; integrity tick pulls mismatched shards from the **advertising** peer (`fromSeq=1`). Readiness `applyLagStale` alone does **not** cover this fence. |
| Proposer missing-stream (follower only) | Phase-ranked proposer’s sealed inventory lists `domain#shard` with FP≠0 while local OpLog tip/applied are 0 **and** local sealed identity is 0 → deny (`stale replica` / incomplete). Evaluated at inventory rebuild into a sticky bit — not per SELECT on disk. Tip-lag-complete ≠ key/stream-complete. Both FP=0 (empty / not yet sealed) and seal-dump lag with `lastSeq>0` are **not** incomplete. Layout drift (both FP≠0 unequal) stays heal-only. |
| Inventory index not ready | Fail-closed incomplete until `helloCatchUp` rebuild publishes the index |
| `replicaReadsEnabled=false` | `REPLICA_READ_DISABLED` |
| Catch-up-only / learner | Always deny client SQL |
| Witness region | Deny replica reads |
| DML / BEGIN on `READ_REPLICA` | `READ_REPLICA_DML_DENIED` (after ANTLR tag) |

**Auto-route writer fallback:** when the URL uses `readPreference=REPLICA` and the read ring is exhausted with an incomplete/stale signal (`ReplicaReadFallbackUtil`), `RoutingConnection` / Sync read paths **resume on the writer** instead of returning a silent empty result. Explicit `forcePrimary` / tooling still pins the writer. Wire `ServerMeta` may carry `catalogFingerprint` for HELLO/AUTH.

No `ALLOW_STALE` in v1. No read-your-writes via replica without a writer round-trip.

## Pros / cons / risks

| Plus | Minus / risk |
|------|----------------|
| Offloads SELECT from the writer | Replica lag → reject and rotate |
| v2 auto-route via shared ANTLR | Classifier must stay aligned with server tags |
| Explicit API still available | Apps can still force `executeRead` |
| Jepsen stays PRIMARY-only | `readEndpoints` in Elle URL is rejected |
| Hold may serve reads when lag OK | Witness never serves |
| Windows seal unmaps `.sbpt` before REPLACE | Seal still competes with IO under load |

## API matrix (closed)

| Client surface | Status |
|----------------|--------|
| `ConnectionFactory.fromUrl` + v2 ANTLR auto-route | **closed** (recommended) |
| `createReadStatement` / `executeRead` | **closed** (explicit, still supported) |
| `RemoteConnectionFactory.createReadFactory` | **closed** (read pool) |
| `staleReadPolicy` | v1 reject-on-stale only (no `ALLOW_STALE`) |
| N `readEndpoints` + rotate on incomplete/stale + writer fallback | ready (product `RoutingConnection` / `ReplicaReadFallbackUtil`) |

Next: [failures](failures.md), [HA promote](ha-promote.md), [replication network](../../understand/replication-network.md).

## Jepsen

`JepsenSqlClient` rejects URLs with `readEndpoints` (Elle contract — PRIMARY only). Do not co-run consistency checks with load on the same host — see [methodology](../../performance/methodology.md).
