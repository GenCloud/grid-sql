# Overlay PIN

`PIN KEY` freezes shard placement for the shard that holds a given key, so the adaptive placement subsystem does not migrate ownership while the annotation is live. It is a placement control, not a memory or query-plan hint: nothing is cached, locked, or pinned in the buffer sense used by some other database products.

## When placement needs freezing

With replication enabled, `AdaptiveReplicaSwarm` scores nodes on a rolling window (load, round-trip time, heap, catch-up cost) and may move shard ownership. On average that balances the cluster. For a small set of keys it is harmful:

- A tenant driving most of a shard's request rate, where a migration means a catch-up spike and a latency regression.
- An interactive session that expects stable locality for its lifetime.
- Rarely written configuration keys sitting on a shard that neighbouring hot keys keep moving.
- An investigation window where ownership must not change while logs and metrics are collected.

Without an explicit control, rebalancing is driven only by metrics. A pin states operator or application intent: do not migrate this shard while the annotation exists.

## Enablement

```yaml
grid:
  overlay:
    enabled: true            # required for PIN / UNPIN
    durable: true            # annotations survive restart
    auto-pin-ttl-ms: 0       # leave at 0 in production
```

| Setting | Default | Effect |
|---------|---------|--------|
| `grid.overlay.enabled` | `false` | Without it, `PIN KEY` is rejected with `overlay is disabled (grid.overlay.enabled=false)` |
| `grid.overlay.durable` | `false` | Persists annotations under `{dataDir}/{cluster}/{node}/overlay/` so they survive a restart |
| `grid.overlay.auto-pin-ttl-ms` | `0` | Above zero, the write path refreshes a short pin on every hot write |

Pins only have an effect where placement would otherwise migrate. On a solo durable node with no peers, `PIN KEY` is accepted and recorded but changes nothing.

## Statements

```sql
PIN KEY <table> <pk-literal> [TTL <ms>] [QOS <tag>];
UNPIN KEY <table> <pk-literal>;
```

```sql
PIN KEY orders 9001 TTL 3600000 QOS 'vip-tenant';
PIN KEY sessions 'sess-abc' TTL 1800000;
PIN KEY config 'root';             -- no expiry: held until UNPIN
UNPIN KEY orders 9001;
```

`TTL` is milliseconds. A pin without `TTL` lives until an explicit `UNPIN`. Re-issuing `PIN` for the same key refreshes the window. When the last pin on a shard is removed or expires, migration may resume on the next placement tick.

## Scope

| Layer | Effect |
|-------|--------|
| Overlay store | Annotation keyed by table name plus key hash, with optional expiry and quality-of-service tag |
| Placement | Migration suppression is per **shard** (`isOverlayBlockingShardMigrate`), derived from the key's shard. Unrelated shards keep migrating |
| Persistence | With `durable: true`, the annotation survives a restart |
| Metric | `grid.replication.overlay_pinned_keys` |

| Layer | Not affected |
|-------|--------------|
| Row bytes, journal, consensus | A pin is an annotation, not a mutation |
| Query plans | There is no plan pin |
| Working set and sealed reads | The row is still subject to normal eviction and miss loading |
| Transaction atomicity and record locks | Unrelated; use transactions and record locks |
| Other shards | Pinning key A does not freeze the shard holding key B |

`hasAnyPinned()` and `isOverlayBlockingSwarmMigrate()` are cluster-wide gauges: they report that at least one pin exists. Only the per-shard check gates a migration decision. Integration coverage: `index.unit.overlay.OverlayPinSqlIT`.

## Procedures

### Freeze a hot tenant for a peak window

1. Identify the shard under pressure from placement events or per-tenant request rate.
2. Pin the tenant key with a window matching the peak:

```sql
PIN KEY orders 9001 TTL 3600000 QOS 'vip-tenant';
```

3. Confirm `grid.replication.overlay_pinned_keys` increased, and that placement ticks no longer apply ownership moves for that shard.
4. Release when the peak ends, or let the expiry do it:

```sql
UNPIN KEY orders 9001;
```

Set the expiry close to the expected peak length. A forgotten pin without `TTL` stops the cluster from ever rebalancing that shard. If the peak runs long, re-issue the statement.

### Freeze placement during an investigation

1. List the shards involved in the incident and pick one representative primary key per shard — suppression is per shard, so one pin does not cover the whole table.
2. Pin them with a window matching the maintenance slot:

```sql
PIN KEY orders 9001 TTL 900000 QOS 'ops-freeze';
PIN KEY config 'root' TTL 900000 QOS 'ops-freeze';
```

3. Complete the diagnosis or take the node out of rotation per the [failure runbook](../operations/failures.md).
4. Remove the freeze tags and confirm the pinned-keys gauge returns to its baseline.

### Hold session locality

For a session-keyed table, the application pins at session start and unpins at session end:

```sql
PIN KEY sessions 'sess-abc' TTL 1800000 QOS 'session';
-- session work
UNPIN KEY sessions 'sess-abc';
```

Set the expiry to the maximum session idle time plus clock skew, so a crashed application does not leave the shard frozen. A pin is not a substitute for a transaction or for writer isolation — the session row still follows normal transaction rules.

### Keep a configuration shard stable

A small, rarely written configuration table can sit on a shard that placement keeps moving because of noisy neighbours. Each migration is pure overhead for its readers:

```sql
PIN KEY config 'root' QOS 'config';
```

Release it only when relocating the domain or decommissioning the node. Use `durable: true` so the pin survives a rolling restart.

### Automatic pinning on hot writes

`auto-pin-ttl-ms` above zero makes the write path refresh a short pin for recently written keys, without application statements:

```yaml
grid:
  overlay:
    enabled: true
    durable: false
    auto-pin-ttl-ms: 60000
```

This is only appropriate for a uniformly hot key distribution. Under bulk ingest a wide write set touches many shards, and because suppression is per shard, the cluster can end up frozen almost everywhere. Keep `auto-pin-ttl-ms: 0` in production and pin known keys explicitly unless a sustained load run proves otherwise.

## Verification

1. Read `grid.replication.overlay_pinned_keys` before and after the statement.
2. Assert `ReplicationCoordinator.isOverlayBlockingShardMigrate(table, shard)` is `true` for the pinned key's shard and `false` for an unrelated shard.
3. Watch placement logs: the pinned shard is skipped, while other shards may still cut over because `swarm.apply-auto-cutover` defaults to `true`.
4. With `durable: true`, restart the node and confirm the pin is still present and still blocking.

## Anti-patterns

| Practice | Why it fails |
|----------|--------------|
| Treating a pin as a buffer or plan pin | It has no memory or optimizer effect |
| Pinning every row touched by a transaction | Annotation churn, and potentially frozen migration across many shards |
| Relying on pins for durability or consistency | Those come from consensus, the journal, sealed files, and transactions |
| Leaving permanent pins without review | The cluster stops rebalancing those shards for good |
| Large `auto-pin-ttl-ms` under bulk ingest | Sustained pins freeze every shard the load touches |
| Expecting one pin to freeze the whole table | Suppression is deliberately scoped to the containing shard |

Placement mechanics: [overlay and swarm](../../understand/overlay-and-swarm.md).

**Related:** [replication](replication.md), [monitoring](../monitoring.md), [role promotion](../operations/ha-promote.md), [failures](../operations/failures.md).
