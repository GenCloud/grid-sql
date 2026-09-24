# Replica reads — client/server methodology

Optional cluster read balance (off by default): **writes stay on the phase-ranked proposer**; **autocommit reads** may target synced voters / Hold. Default remains PRIMARY-only writer pin (unchanged Jepsen / Elle contract).

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

### FOR UPDATE and distributed peers

`FOR UPDATE` / `SKIP LOCKED` always run on the **writer** (never a read replica). With
`DistForUpdatePeerLockAgent`s configured (from `grid.sql.distributed-peers` / Boot wiring),
indexed wire keys are locked **locally** via `LockAwareKeyCursor` and on **peers** via
`DistForUpdateCoordinator` (Netty `FOR_UPDATE_LOCK_*`, fail-closed). Autocommit releases
statement peer leases in `finally`; open-TX leases stay until COMMIT/ROLLBACK.
Prepare/commit-dec votes (`DistForUpdatePrepareVotes` + Netty `FOR_UPDATE_PREPARE_*` / `COMMIT_DEC`) are product-wired 2PC-lite for peer row locks — not a full XA 2PC. Multi-table / INNER JOIN `FOR UPDATE` locks are supported.

If `grid.sql.distributed-peers` is empty or unset, locks stay **local** on the writer only.

```yaml
grid:
  sql:
    distributed-peers:
      - { host: 127.0.0.1, port: 5616 }   # peer agent endpoint as wired
```

### v2 auto-route (recommended)

With `readEndpoints` configured, `ConnectionFactory.fromUrl(url)` returns a routing `Connection` that classifies SQL via shared ANTLR **`SqlRouteClassifier`**:

- **READ** (plain SELECT / EXPLAIN without locking) → least-inflight read endpoint (`ReadEndpointSelector`)
- **WRITE** / TX / DDL / PREPARE / `FOR UPDATE` → pinned writer

N `readEndpoints` are supported (`host:port` list). Prefer **`ConnectionFactory.fromUrl`** — routing is automatic; no manual dual-factory wiring.

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

### Write URL (pinned proposer)

```
grid://user:pass@127.0.0.1:15432,127.0.0.1:15433/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2
```

Authority = failover ring for writer discovery (`PROMOTE_NOTIFY` / AUTH `ServerMeta`). Mid-op fence -> `rediscoverWriter()`.

### Read URL extras (N endpoints)

```
grid://user:pass@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433,127.0.0.1:15434&readPreference=REPLICA&maxReadConnections=2
```

| Query | Meaning |
|-------|---------|
| `readEndpoints` | Comma `host:port` read ring (N≥1 required when `readPreference=REPLICA`) |
| `readPreference` | `PRIMARY` (default) or `REPLICA` |
| `maxReadConnections` | TCP cap for read factory (default 1) |
| `staleReadPolicy` | v1: `FAIL_CLOSED` only |

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

## Stale / lag policy

| Condition | Behavior |
|-----------|----------|
| `applyLagStale` | Reject `REPLICA_READ_STALE`; rotate on next `obtain()` / statement boundary |
| `replicaReadsEnabled=false` | `REPLICA_READ_DISABLED` |
| Catch-up-only / learner | Always deny client SQL |
| Witness region | Deny replica reads |
| DML / BEGIN on `READ_REPLICA` | `READ_REPLICA_DML_DENIED` (after ANTLR tag) |

No `ALLOW_STALE` in v1. No read-your-writes via replica without a writer round-trip.

## Pros / cons / risks

| Plus | Minus / risk |
|------|----------------|
| Offloads SELECT from proposer | Replica lag -> FAIL_CLOSED rejects / rotate |
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
| `staleReadPolicy` | v1 **FAIL_CLOSED** only (no `ALLOW_STALE`) |
| N `readEndpoints` + `ReadEndpointSelector` rotate on `applyLagStale` | **closed** (IT: `ReplicaReadEndpointsRotateIT`) |

## Related

- [failures.md](failures.md)
- [ha-promote.md](ha-promote.md)
- [cluster-ha-highload.md](cluster-ha-highload.md)
- [replication-network.md](../../understand/replication-network.md)

## Jepsen

`JepsenSqlClient` rejects URLs with `readEndpoints` (Elle contract — PRIMARY only). Do not co-run consistency checks with load on the same host — see [methodology](../../performance/methodology.md).
