# Index lag measure — 2026-09-22-v1-gates-index-lag

Calm host. Product upsert = sync indexNow; async add = staged drain path.

| Field | Value |
|-------|--------|
| stamp | `2026-09-22-v1-gates-index-lag` |
| syncRows (putIndexed) | 2000 |
| syncWallMs | 125 |
| syncMaxPending | 0 (expected 0) |
| syncSelectKeys | n=50 |
| asyncRows (worker.add) | 5000 |
| asyncEnqueueMs | 56 |
| asyncMaxPending | 5000 |
| asyncDrainMs | 47 |
| regress | **no** — product sync lag≈0; async drain 47 ms for 5000 rows; no squeeze |

No floor/HA weaken.
