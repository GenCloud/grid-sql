# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent `:valid? true`).

## Latest stamp (ASYNC+SYNC — residuals gates)

| Field | Value |
|-------|--------|
| stamp | `2026-09-22-residuals-gates-multidc` |
| date | 2026-09-22T21:41:54.6992233+03:00 |
| git | cba172f |
| host | DESKTOP-4IC511D |
| mode | ASYNC_SHIP then SYNC_VOTERS_ACROSS_DC (topology 3+2), calm sequential |
| outcome | `PASS` |
| ASYNC_SHIP register | PASS (:valid? true) |
| ASYNC_SHIP append | PASS (:valid? true) |
| SYNC_VOTERS register | PASS (:valid? true) |
| SYNC_VOTERS append | PASS (:valid? true) |
| chaos | ASYNC: full dc-link+kill-voter+kill-dc-a; SYNC: dc-link+kill-voter (register conc=1 for Knossos) |
| multi-host SQL | `grid://@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public` |
| notes | Post residual wave; twin 1-DC `2026-09-22-residuals-gates-jepsen-1dc`; time-limit=30; harness: mid-op connect=info for mutations; no floor weaken |

## History

| stamp | mode | register | append | outcome | notes |
|-------|------|----------|--------|---------|-------|
| `2026-09-22-residuals-gates-multidc` | ASYNC+SYNC | PASS (:valid? true) | PASS (:valid? true) | PASS | residuals calm gates; async+sync stamps `...-multidc-async` / `...-multidc-sync` |
| `2026-09-22-residuals-gates-multidc-async` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=30 |
| `2026-09-22-residuals-gates-multidc-sync` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=30; register concurrency 1 |
| `2026-09-22-v1-gates-multidc` | ASYNC+SYNC | PASS (:valid? true) | PASS (:valid? true) | PASS | calm sequential after Waves 0–2; 1-DC twin `2026-09-22-v1-gates-jepsen-1dc` |
| `2026-09-17-multidc-async` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=60 |
| `2026-09-17-multidc-sync-voters` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=60 |
| `2026-09-18-aqe-residuals` (re-run) | ASYNC+SYNC | PASS (:valid? true) | PASS (:valid? true) | PASS | calm sequential after AQE leftovers |
| `2026-09-20` TD residuals (Phase F) | ASYNC_SHIP then SYNC_VOTERS | PASS (:valid? true) | PASS (:valid? true) | PASS | 1-DC twin `2026-09-20-jepsen-full` |
| `2026-09-18-multidc-sync-voters` | SYNC_VOTERS_ACROSS_DC | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=30 |
| `2026-09-18-multidc-async` | ASYNC_SHIP | PASS (:valid? true) | PASS (:valid? true) | PASS | time-limit=60 |

Parent 1-DC: [../RESULTS.md](../RESULTS.md).