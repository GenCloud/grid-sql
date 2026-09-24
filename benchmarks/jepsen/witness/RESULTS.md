# Witness Jepsen RESULTS

Honest PASS/FAIL after Docker+lein Witness overlay (never invent `:valid? true`).

## Latest stamp (ops soak — TD-HA-002)

| Field | Value |
|-------|--------|
| stamp | `2026-09-22-v1-gates-witness-soak` |
| date | 2026-09-22T15:35:06.3193414+03:00 |
| git | 18f8b29 |
| host | DESKTOP-4IC511D |
| topology | Active+Hold+Hold+Witness (async Multi-DC + w1) |
| outcome | `PASS` |
| checklist | (1) multi-host cutover under write — HA WRITE r4 `apply-auto-cutover=true` + light write on Witness compose; (2) restart Hold b1 + Witness w1 → all 5 `/health/liveness` + readiness_ok=5; (3) durable PIN — `OverlayDurableStoreTest` + `OverlayPinSqlIT` PASS |
| IT | OverlayDurableStoreTest, OverlayPinSqlIT, ApplyAutoCutoverIT, SqlRegionEpochPromoteIT#claimAdvancesEpochAndWriterEligibleAfterQuorum PASS |
| light write | `2026-09-22-v1-gates-witness-soak-light-write` PASS tps=851.3 (floor=1) |
| notes | Closes TD-HA-002; twin chaos `2026-09-21-witness-chaos` remains validity stamp |

## Operator soak checklist (TD-HA-002)

1. Multi-host cutover with `apply-auto-cutover: true` under light write load — **Done**
2. Restart Hold + Witness; confirm REGION_CLAIM re-forms single Active — **Done** (post-restart readiness 5/5; claim IT PASS)
3. Durable overlay `PIN` survives restart and suppresses migrate — **Done** (OverlayDurableStoreTest + OverlayPinSqlIT)
4. Record stamp in this RESULTS + capacity notes — **Done** (this stamp)

## History

| stamp | register | append | outcome | notes |
|-------|----------|--------|---------|-------|
| `2026-09-22-v1-gates-witness-soak` | ops soak | ops soak | PASS | TD-HA-002 close; IT+restart+PIN |
| `2026-09-21-witness-chaos` | PASS (:valid? true) | PASS (:valid? true) | PASS | register=PASS (:valid? true); append=PASS (:valid? true); time-limit=30; Witness w1 overlay |

Parent Multi-DC: [../multidc/RESULTS.md](../multidc/RESULTS.md). Debt: TD-HA-002 **Fixed**.