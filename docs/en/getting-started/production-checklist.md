# Production checklist

Everything to settle before a Grid cluster takes production traffic. Each item names the setting or command, why it matters, and the page with the full procedure. Work top to bottom: storage, cluster, recovery, access, observability, clients.

## 1. Storage and durability

| Check | Setting | Why |
|-------|---------|-----|
| Durability enabled on every node that must survive a restart | `grid.durability.enabled: true` | Writes pass through ORCHID and the OpLog **before** becoming visible; a failure aborts the commit instead of leaving it half applied |
| `fsync` on the OpLog | `grid.replication.op-log.fsync: true` | Without it, commits acknowledged just before a crash can be lost. `fsync: false` is a lab tool for isolating a bottleneck |
| One `dataDir` per node, on local disk | `grid.replication.op-log.data-dir`, node `dataDir` | Two processes over one directory, or a cluster-wide NFS/SAN volume, corrupt segments and sealed files |
| Hydrate mode matched to the dataset | `grid.durability.hydrate-mode: LAZY` | `LAZY` maps sealed files and loads keys on miss — the usual production choice. `FULL` preloads everything and can make start slow or run out of memory on a large sealed set |
| Working-set ceiling set | `grid.durability.working-set-max-entries` | Caps RAM by evicting cold committed keys; dirty and staging entries are never evicted |
| Disk headroom for OpLog plus sealed files plus archive | — | Truncate only happens through the seal watermark, and with archiving on, a copy failure cancels truncate |

Full reference: [durability](../configure-and-operate/configuration/durability.md), [storage](../understand/storage-sealed-gmap.md).

## 2. Cluster and the single writer

| Check | Setting | Why |
|-------|---------|-----|
| Durability and replication decided separately | `grid.durability.enabled`, `grid.replication.enabled` | A solo durable node is a supported mode; replication adds peer shipping, quorum, and catch-up on top |
| `node-id`, `cluster-id`, `transport`, and `peers` set per node | node YAML | Starter profiles are demos; a foreign `cluster-id` blocks catch-up |
| Exactly one writer | — | The phase-ranked proposer is the only writer; there is no dual-master merge |
| Clients pin the writer from protocol metadata | `ServerMeta`, `PROMOTE_NOTIFY` | HTTP health is for orchestrators, not for writer discovery |
| SQL and replication ports kept distinct | SQL **15432** / **15433**, replication **5615** / **5616** | A client that lands on a replication port gets `bad frameLen …` |
| Region roles and quorum reviewed for multi-site | `grid.replication.region.*` | Active/Hold fencing and the claim quorum decide who may write after a site is lost |

Full reference: [replication](../configure-and-operate/configuration/replication.md), [promote a node](../configure-and-operate/operations/ha-promote.md), [multi-site](../configure-and-operate/operations/multi-dc.md).

## 3. Backup and recovery

| Check | Setting or tool | Why |
|-------|-----------------|-----|
| OpLog archive enabled **before** the first load you might roll back | `grid.durability.oplog-archive.enabled: true`, own `dir` per node | Turning it on during an incident gives you nothing to restore from |
| Base backups on a schedule | `SealedBaseBackupUtil` | A restore needs a sealed base at watermark `W` plus archive segments `[W+1 … T]` |
| Archive coverage verified after truncate | — | If the archive does not cover the tail past `W`, a restore to `T` stops short |
| Restore practised offline on a copy of `dataDir` | `PitrRestoreMain --until-seq` | Never rehearse on the live writer. A failed drill is an operational defect to fix before the real incident |
| Cross-site restore fenced | `PitrCoordinatedRestore`, `PitrActiveFence` | Restoring every site at once risks two writers |

Full reference: [PITR](../configure-and-operate/operations/pitr.md), [backup and restore](../configure-and-operate/operations/backup-restore.md).

## 4. Access and network

| Check | Action | Why |
|-------|--------|-----|
| SQL port reachable only from trusted networks | Firewall or load balancer in front of **15432** | While the user catalog is empty, anyone who reaches the port can create the administrator |
| First administrator created before traffic | `CREATE USER admin PASSWORD '…'` | Once a user exists, frames without AUTH are rejected |
| Application users with least privilege | `GRANT` / `REVOKE` on schema and tables | Privileges are enforced on both sides of a JOIN |
| TLS terminated in front of the node | Reverse proxy or load balancer | The product does not serve TLS on the SQL port |
| Replication path kept node-to-node | **5615** / **5616** | The replication transport is a different protocol and must never be published publicly |
| Credential rotation procedure agreed | `ALTER USER … PASSWORD`, then roll application URLs | Open sessions can hold old rights until they reconnect |

Full reference: [security](../configure-and-operate/operations/security.md).

## 5. Observability

| Check | Endpoint or metric | Why |
|-------|--------------------|-----|
| Liveness probe wired | `/health/liveness` (`gridLiveness`) | Process and logic executor alive |
| Readiness probe wired and honoured | `/health/readiness` (`gridReadiness`) | SQL TCP bound, and with replication on, ORCHID synced. A node that is not ready must not take traffic |
| Prometheus scrape configured | `/prometheus` | `grid.replication.orchid_r`, `repair_issued` / `repair_applied`, `rpo_estimate_ms` |
| Alerts defined | See below | Signals that precede an outage |
| Write-path stage split understood | `orchidWaitP99Ns` vs `oplogFsyncP99Ns` | ORCHID rising points at peer network or the `R` threshold; fsync rising points at disk |

Alert at least on: readiness DOWN after warm-up; `applyLagStale: true` on a read replica; `orchid_r` below the admission threshold; OpLog fsync latency or disk pressure climbing; `repair_issued` growing while `repair_applied` does not.

Full reference: [monitoring](../configure-and-operate/monitoring.md), [failures](../configure-and-operate/operations/failures.md).

## 6. Clients

| Check | Action | Why |
|-------|--------|-----|
| One `ConnectionFactory` per process, disposed on shutdown | `factory.dispose()` | One TCP connection carries many transactions; a socket pool adds nothing |
| Concurrency budget set | `maxTxContexts` | Soft cap on concurrent transactions over one socket |
| Writer changes handled through the protocol | `rediscoverWriter()`, `PROMOTE_NOTIFY` | Rotating to the next host in the URL after a reject risks landing on a non-writer or a stale epoch |
| Timeouts and retries set explicitly | `connectTimeoutMs`, `execTimeoutMs`, `retryMode` / `maxRetries` | Defaults leave `execTimeoutMs` off and retries disabled |
| Replica read semantics accepted where enabled | `readEndpoints`, `readPreference` | Reading your own writes through a replica is not guaranteed; stale reads abort |
| Large result sets fetched in windows | `fetchWindow` | Avoids materialising a full result on the client |
| `.block()` only at the application edge | `main`, CLI, tests | Blocking inside a reactive pipeline stalls the event loop |

Full reference: [connect clients](connect-clients.md), [Java client](../develop/java-client.md), [replica reads](../configure-and-operate/operations/replica-reads.md).

## 7. Change management

| Check | Action | Why |
|-------|--------|-----|
| Upgrades are a rolling restart against the same `dataDir` | [upgrade](../configure-and-operate/operations/upgrade.md) | Data stays on disk; the node rehydrates from sealed files and the OpLog |
| Schema changes run in autocommit | — | DDL inside an open transaction is rejected |
| Shard auto-cutover left on unless there is a reason | `apply-auto-cutover` | Disabling it is a temporary measure — [overlay and placement](../understand/overlay-and-swarm.md) |
| Capacity figures measured with `fsync: true` on an idle host | [capacity](../performance/capacity-slo.md) | Numbers taken without fsync, or with concurrent checks, are not a planning basis |

## Go-live gate

Before the first production write, confirm: durability on with `fsync: true`; `dataDir` local and per node; one writer and clients pinned through `ServerMeta` / `PROMOTE_NOTIFY`; AUTH enabled with least-privilege grants; OpLog archive on with a base backup taken and a restore rehearsed; liveness and readiness probes wired with alerts in place.

**Related:** [best practices](best-practices.md), [start a cluster](start-cluster.md), [durability](../configure-and-operate/configuration/durability.md), [monitoring](../configure-and-operate/monitoring.md), [PITR](../configure-and-operate/operations/pitr.md).
