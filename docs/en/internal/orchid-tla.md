# ORCHID TLA+ / TLC

Formal model of the ORCHID protocol, so that races for the writer role and for a commit are checked by exhaustive state exploration and not only by tests.

## Specs and configs

| Spec | Config | Scope |
|------|--------|-------|
| [`spec/orchid/OrchidLog.tla`](../../spec/orchid/OrchidLog.tla) | [`OrchidLog.cfg`](../../spec/orchid/OrchidLog.cfg) / [`OrchidLog-fast.cfg`](../../spec/orchid/OrchidLog-fast.cfg) | **Hard gate**, single site: `Nodes={n1,n2,n3}`, `MaxSeq=3` |
| same | [`OrchidLog-heavy.cfg`](../../spec/orchid/OrchidLog-heavy.cfg) | **Optional, nightly**: `MaxSeq=5`, same spec — partitions, nack competitor, forget and heal |
| [`spec/orchid/OrchidLogMultiDc.tla`](../../spec/orchid/OrchidLogMultiDc.tla) | [`OrchidLogMultiDc.cfg`](../../spec/orchid/OrchidLogMultiDc.cfg) | **Hard gate**: local voters plus remote digest voter `r1`, `MaxSeq=2` |
| [`spec/orchid/RegionClaim.tla`](../../spec/orchid/RegionClaim.tla) | [`RegionClaim.cfg`](../../spec/orchid/RegionClaim.cfg) | **Hard gate**: `AtMostOneActive`; log in `tlc-out/last-run-region-claim.log` |

## Modelled, single site

- Voter membership is fixed by config: forgetting a node does not shrink the quorum.
- A single phase-ranked proposer; a competing propose cannot commit (`NackCompetitor`).
- Contiguous `prevOpSeq`, with `NoFork` held on the slot digest.
- A minority partition cannot commit; the majority partition and subsequent heal are modelled.
- `persisted` happens before followers advance `lastCommitted`.

## Modelled, multi-site

With `SYNC_VOTERS_ACROSS_DC` configured, TLC proves:

```
Commit => LocalQuorum(localReachable) /\ RemoteDigestsMet
```

- Remote voters acknowledge digests over a separate WAN path (`RemoteAck`) and are **not** part of `localReachable` (`phaseCoupling=false`, invariant `RemoteNotInLocalR`).
- `PartitionRemote` empties `remoteReachable`, so a commit is blocked even with a local majority: the write is refused rather than partially applied.
- This is **not** LAN phase coupling stretched across a WAN.

## Run

```bash
./scripts/run-tlc-orchid.sh
pwsh ./scripts/run-tlc-orchid.ps1
# heavy variant, run alone; not a CI hard gate:
./scripts/run-tlc-orchid.sh --heavy
pwsh ./scripts/run-tlc-orchid.ps1 -Heavy
```

The hard gate is fast single-site plus multi-site plus RegionClaim. An invariant violation exits non-zero. There is **no** GitHub Actions job named `tlc-orchid` — run TLC locally via the scripts above, or as the first hard step of `scripts/run-perf-gate.{ps1,sh}` on a calm host (before optional Jepsen). Unit CI is `.github/workflows/ci.yml`; consistency matrix is `jepsen-qg.yml`.

## State bounds

Wall-clock time for the fast models is about a second, which is normal for bounded state spaces — assert PASS and the state counts, not a duration.

| Spec | Distinct states | Generated |
|------|----------------:|----------:|
| OrchidLog fast (`MaxSeq=3`, 3 nodes) | **1704** | 8441 |
| OrchidLogMultiDc (`MaxSeq=2`) | **408** | 2299 |
| OrchidLog heavy (`MaxSeq=5`) | **24060** | ~119573 |

Depth may land on 11 or 12 depending on TLC exploration order; distinct counts are stable for the checked-in `.cfg` constants. Multi-site invariants checked: `TypeOK`, `SingleSlotAgreement`, `LogPrefix`, `NoForkEq`, `DurableBeforeAdvance`, `ConfiguredQuorumFixed`, `RemoteNotInLocalR`.

Logs land in `spec/orchid/tlc-out/`: `last-run.log`, `last-run-multidc.log` and, for the heavy run, `last-run-heavy.log`.

## Related

- [ORCHID consensus](../understand/orchid-consensus.md)
- [Development](development.md)

Russian: [orchid-tla.md](../../ru/internal/orchid-tla.md).
