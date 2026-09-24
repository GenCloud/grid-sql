# Glossary

Short definitions for the terms used across the operations and architecture pages. API and YAML names (`writerEligible`, `peers:`) are written as they appear in code; the table explains meaning only.

| Term | Meaning |
|------|---------|
| ORCHID | Node agreement: phase synchronization (after Yoshiki Kuramoto) plus a digest quorum |
| Order parameter `R` | How closely phases agree among the live peers a node sees (local site by default) |
| Digest quorum | Majority agreement on one propose digest before a commit becomes visible |
| Phase-ranked proposer | The single active writer among synced nodes (`min(nodeId)` among self and seen peers) |
| OpLog | Append-only journal of mutations |
| Sealed GMAP | On-disk `.gmap` files; together with OpLog they are the durable store |
| `.sbpt` / `.sbm` | Sealed secondary B+ tree and bitmap index |
| Sealed miss | Working-set miss: the key is loaded from sealed files into RAM |
| Working set | In-memory `GridScalableMap` and indexes; an accelerator, not the sole source of truth when durability is on |
| Hydrate | Loading sealed files and OpLog into the working set at start (`FULL` or `LAZY`) |
| PIN | Marker that keeps a key's shard from migrating while it is alive (overlay layer) |
| Ownership cutover | Shard ownership change after catch-up (`CUTOVER_DONE`); see [placement](../understand/overlay-and-swarm.md) |
| `grid://` | SQL URL of the reactive client |
| `jdbc:grid://` | SQL URL of the JDBC client (same protocol) |
| `maxTxContexts` | Cap on logical sessions on one TCP. **Client** URL default **256**; **server** channel hard-cap **8** (Boot does not raise it from YAML) — open another `Connection` when exhausted |
| `writerEligible` | Wire metadata: the node may accept writes |
| `regionEpoch` | Epoch counter of the Active site under region fencing |
| AQE | Adaptive Query Execution: parallel scan and `DIST_MAP` for heavy SELECTs |
| ASYNC_SHIP / SYNC_VOTERS | Cross-site journal modes: asynchronous ship, or synchronous digest voters |
| HomologousRepair | Locus-based catch-up and reship of missing operations |
| AdaptiveReplicaSwarm | Placement hints that may migrate shard ownership |
| SqlCli | Interactive shell in `grid-sql-client` (synchronous `main` boundary) |
| `PROMOTE_NOTIFY` | Wire frame telling the client that the writer has changed |
| SparseCatchUp | Catching a lagging node up with an OpLog range instead of shipping the whole journal |
| Run id | Name of one calm-host load or JMH run in its JSON and in `SUMMARY.md` |
| Write admission | The ORCHID condition — phase `R` and digest quorum — under which a commit becomes visible |
| Writer (`proposer`) | The node that currently accepts writes, phase-ranked among synced peers |
| Witness | Cross-site witness node: joins the claim quorum, serves neither writes nor reads |
| Region isolation | `grid.replication.region.enabled`: Active / Hold / Witness roles and `regionEpoch` instead of a fixed primary site |
| Catch-up | Bringing a lagging node forward (SparseCatchUp, HomologousRepair) rather than reinstalling it |
| Health readiness | Actuator probe: SQL is listening and, with replication on, ORCHID is synced; otherwise do not send traffic |

## Do not confuse

- **OpLog is not sealed.** The journal is the mutation stream; sealed files (`.gmap`, `.sbpt`) are the compacted snapshot. Sealed files alone will not recover recent commits.
- **Durability is not replication.** `grid.durability.enabled` gives local ORCHID, OpLog and sealed files, and works on a single node. `grid.replication.enabled` adds peers, quorum and catch-up.
- **Working set is not the source of truth.** With durability on, RAM is an accelerator; the journal and sealed files are authoritative.

Terms not listed here are defined on the section page. Current load planning figures: [capacity](../performance/capacity-slo.md).

## Related

- [Introduction](../getting-started/what-is-grid.md)
- [Architecture overview](../understand/architecture-overview.md)
- [ORCHID consensus](../understand/orchid-consensus.md)
- [Replication configuration](../configure-and-operate/configuration/replication.md)

Russian: [glossary.md](../../ru/tools/glossary.md).
