# Jepsen RESULTS

Stamp template - filled by `scripts/run-jepsen-smoke.*` or a full Jepsen run.

## Latest stamp

| Field | Value |
|-------|--------|
| stamp | 2026-10-05-solo-1dc-unclean-revive |
| date | 2026-10-05T14:02:23.9998716+03:00 |
| git | c2aca8b |
| host | DESKTOP-4IC511D |
| mode | `full-jepsen` |
| outcome | `PASS` |
| notes | register=PASS; append=PASS |

## History
### Full Multi-DC 3+2 (stamp 2026-09-18-aqe-residuals)
| Mode | register | append | outcome |
|------|----------|--------|---------|
| ASYNC_SHIP | `:valid? true` | `:valid? true` | PASS |
| SYNC_VOTERS_ACROSS_DC | `:valid? true` | `:valid? true` | PASS |
Calm sequential re-run after AQE leftovers. Details: [multidc/RESULTS.md](multidc/RESULTS.md).

### Full Multi-DC 3+2 (stamp 2026-09-17-multidc-*)
| Mode | register | append | outcome |
|------|----------|--------|---------|
| ASYNC_SHIP | `:valid? true` | `:valid? true` | PASS |
| SYNC_VOTERS_ACROSS_DC | `:valid? true` | `:valid? true` | PASS |
Details + p50/p95/p99: [multidc/RESULTS.md](multidc/RESULTS.md).

### Algorithm gate - nochao 60s (stamp 2026-09-15-orchid-qos-h)
| Workload | Op | n | p50 (ms) | p95 (ms) | p99 (ms) | Gate | Result |
|----------|-----|---|----------|----------|----------|------|--------|
| register | write | 246 | **6.982** | **26.013** | 34.680 | p50<=13.335; p95<=92.505 | PASS |
| append | txn `:append` | 254 | **7.281** | **22.773** | 34.488 | p50<=12.495; p95<=38.640 | PASS |
| append | txn `:r` | 267 | 2.490 | **3.969** | 13.189 | p95<=26.250 | PASS |

### 2026-09-15-gaps-features
- mode: full-jepsen
- outcome: PASS
- git: a916ec2
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-17-post-impl
- mode: full-jepsen
- outcome: PASS
- git: a916ec2
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (S8 post-impl)
- notes: register=PASS; append=PASS; :valid? true (Elle+timeline)

### 2026-09-17-key-probe
- mode: full-jepsen
- outcome: PASS
- git: a916ec2
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-17-rerun-calm
- mode: full-jepsen
- outcome: PASS
- git: a916ec2
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-17-vector-fix
- mode: full-jepsen
- outcome: PASS
- git: a916ec2
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-18-aqe-residuals
- mode: full-jepsen
- outcome: PASS
- git: 0cdedb7
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-18-aqe-residuals-multidc-sync
- mode: full-jepsen
- outcome: PASS
- git: 0cdedb7
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-18-aqe-residuals-multidc-sync
- mode: full-jepsen
- outcome: PASS
- git: 7f31f11
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-18-lockfix
- mode: full-jepsen
- outcome: PASS
- git: 7f31f11
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-18-residuals-r0
- mode: full-jepsen
- outcome: PASS
- git: 597b18f
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-19-jepsen-full
- mode: full-jepsen
- outcome: PASS
- git: d7c8564
- compose: up
- chaos-it: not-run
- full-jepsen: PASS
- command: run-jepsen.sh register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-18-residuals-r0
- mode: full-jepsen
- outcome: PASS
- git: d7c8564
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-19-ooo-fix-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: 99a05aa
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-20-jepsen-full
- mode: full-jepsen
- outcome: PASS
- git: 4898105
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-20-refactor-query
- mode: full-jepsen
- outcome: PASS
- git: 0330442
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-20-jepsen-full
- mode: full-jepsen
- outcome: PASS
- git: 0330442
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-21-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: 371dec9
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-21-dialect-gates-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: 79801fe
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-22-trg-wire-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: 18f8b29
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-22-v1-gates-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: 18f8b29
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-22-residuals-gates-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: cba172f
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-23-lazy-select-hydrate-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: edb9ee1
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-23-jooq-dx-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: 34739c1
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-23-jdbc-sync-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: 34739c1
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-23-prod-prep-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: 8633c43
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-23-style-refactor-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: 8633c43
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-23-style-refactor-jepsen-1dc-r2
- mode: full-jepsen
- outcome: PASS
- git: 8633c43
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-24-dialect-full-jepsen
- mode: full-jepsen
- outcome: PASS
- git: a12685a
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-25-dialect-builtins-qg
- mode: full-jepsen
- outcome: PASS
- git: 637875d
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-25-jepsen-full
- mode: full-jepsen
- outcome: PASS
- git: unknown
- compose: up
- chaos-it: not-run
- full-jepsen: PASS
- command: run-jepsen.sh register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-26-composite-prefix-jepsen
- mode: full-jepsen
- outcome: PASS
- git: 47e2276
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-26-select-star-jepsen
- mode: full-jepsen
- outcome: PASS
- git: 47e2276
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-26-select-star-multidc-sync-r2
- mode: full-jepsen
- outcome: PASS
- git: a2f095e
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-27-schema-jepsen
- mode: full-jepsen
- outcome: PASS
- git: 95044fe
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-27-nosynth-jepsen
- mode: full-jepsen
- outcome: PASS
- git: 95044fe
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-29-jepsen-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 1210357
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-29-jepsen-full
- mode: full-jepsen
- outcome: PASS
- git: 1210357
- compose: up
- chaos-it: not-run
- full-jepsen: PASS
- command: run-jepsen.sh register+append (time-limit=45)
- notes: register=PASS; append=PASS

### 2026-09-29-jepsen-full
- mode: full-jepsen
- outcome: PASS
- git: 1210357
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-29-jepsen-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: 1210357
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-30-jepsen-full
- mode: full-jepsen
- outcome: PASS
- git: unknown
- compose: up
- chaos-it: not-run
- full-jepsen: PASS
- command: run-jepsen.sh register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-09-30-sess-jepsen-1dc
- mode: full-jepsen
- outcome: PASS
- git: dac901a
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=30)
- notes: register=PASS; append=PASS

### 2026-09-30-jfr-rb-jepsen-1dc
- mode: smoke
- outcome: HARNESS_READY
- git: 47a1e71
- compose: validated
- chaos-it: pass
- full-jepsen: not-run
- command: run-jepsen-smoke.ps1
- notes: compose config ok; chaos ITs pass; full Jepsen not run (lein absent on Windows)

### 2026-10-02-swarm-pitr-aqe-jepsen-full
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-10-02-wave-align-jepsen-full
- mode: full-jepsen
- outcome: FAIL
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: FAIL
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=FAIL

### 2026-10-02-wave-align-jepsen-full-r2
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=40)
- notes: register=PASS; append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=40)
- notes: register=PASS; append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=40)
- notes: register=PASS; append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-swarm-chaos
- mode: 1dc-swarm-chaos
- outcome: PASS
- git: 47a1e71
- compose: up-swarm
- chaos-it: partition+kill+swarm-bounce
- full-jepsen: PASS
- command: run-jepsen-swarm.ps1 append (time-limit=40)
- notes: swarm-append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-join-shards
- mode: 1dc-join-shards-chaos
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen-join.ps1 join (time-limit=40)
- notes: join-shards=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=40)
- notes: register=PASS; append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=40)
- notes: register=PASS; append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-swarm-chaos
- mode: 1dc-swarm-chaos
- outcome: PASS
- git: 47a1e71
- compose: up-swarm
- chaos-it: partition+kill+swarm-bounce
- full-jepsen: PASS
- command: run-jepsen-swarm.ps1 append (time-limit=40)
- notes: swarm-append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-join-shards
- mode: 1dc-join-shards-chaos
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen-join.ps1 join (time-limit=40)
- notes: join-shards=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=40)
- notes: register=PASS; append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-nochao
- mode: nochao
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: no-nemesis
- full-jepsen: PASS
- command: run-jepsen-nochao.ps1 register+append (time-limit=40)
- notes: register=PASS; append=PASS; no-nemesis

### 2026-10-02-jepsen-edge-matrix-1dc-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=40)
- notes: register=PASS; append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-swarm-chaos
- mode: 1dc-swarm-chaos
- outcome: PASS
- git: 47a1e71
- compose: up-swarm
- chaos-it: partition+kill+swarm-bounce
- full-jepsen: PASS
- command: run-jepsen-swarm.ps1 append (time-limit=60)
- notes: swarm-append=PASS

### 2026-10-02-jepsen-edge-matrix-1dc-join-shards
- mode: 1dc-join-shards-chaos
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen-join.ps1 join (time-limit=40)
- notes: join-shards=PASS

### 2026-10-03-client-smoke-nochao
- mode: nochao
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: no-nemesis
- full-jepsen: PASS
- command: run-jepsen-nochao.ps1 register+append (time-limit=40)
- notes: register=PASS; append=PASS; no-nemesis

### 2026-10-03-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-nochao
- mode: nochao
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: no-nemesis
- full-jepsen: PASS
- command: run-jepsen-nochao.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS; no-nemesis

### 2026-10-03-jepsen-edge-matrix-1dc-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-swarm-chaos
- mode: 1dc-swarm-chaos
- outcome: PASS
- git: 47a1e71
- compose: up-swarm
- chaos-it: partition+kill+swarm-bounce
- full-jepsen: PASS
- command: run-jepsen-swarm.ps1 append (time-limit=60)
- notes: swarm-append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-join-shards
- mode: 1dc-join-shards-chaos
- outcome: PASS
- git: 47a1e71
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen-join.ps1 join (time-limit=45)
- notes: join-shards=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: FAIL
- git: 338c881
- compose: up
- chaos-it: partition+kill
- full-jepsen: FAIL
- command: run-jepsen.ps1 register+append (time-limit=45)
- notes: register=PASS; append=FAIL

### 2026-10-03-jepsen-edge-matrix-1dc-nochao
- mode: nochao
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: no-nemesis
- full-jepsen: PASS
- command: run-jepsen-nochao.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS; no-nemesis

### 2026-10-03-tip-burst-recheck-1dc-chaos
- mode: full-jepsen
- outcome: FAIL
- git: 338c881
- compose: up
- chaos-it: partition+kill
- full-jepsen: FAIL
- command: run-jepsen.ps1 register+append (time-limit=45)
- notes: register=FAIL; append=FAIL

### 2026-10-03-recheck-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-nochao
- mode: nochao
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: no-nemesis
- full-jepsen: PASS
- command: run-jepsen-nochao.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS; no-nemesis

### 2026-10-03-jepsen-edge-matrix-1dc-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-swarm-chaos
- mode: 1dc-swarm-chaos
- outcome: PASS
- git: 338c881
- compose: up-swarm
- chaos-it: partition+kill+swarm-bounce
- full-jepsen: PASS
- command: run-jepsen-swarm.ps1 append (time-limit=60)
- notes: swarm-append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-join-shards
- mode: 1dc-join-shards-chaos
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen-join.ps1 join (time-limit=45)
- notes: join-shards=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-nochao
- mode: nochao
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: no-nemesis
- full-jepsen: PASS
- command: run-jepsen-nochao.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS; no-nemesis

### 2026-10-03-jepsen-edge-matrix-1dc-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=45)
- notes: register=PASS; append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-swarm-chaos
- mode: 1dc-swarm-chaos
- outcome: PASS
- git: 338c881
- compose: up-swarm
- chaos-it: partition+kill+swarm-bounce
- full-jepsen: PASS
- command: run-jepsen-swarm.ps1 append (time-limit=60)
- notes: swarm-append=PASS

### 2026-10-03-jepsen-edge-matrix-1dc-join-shards
- mode: 1dc-join-shards-chaos
- outcome: PASS
- git: 338c881
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen-join.ps1 join (time-limit=45)
- notes: join-shards=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 770e79c
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-nochao
- mode: nochao
- outcome: PASS
- git: 770e79c
- compose: up
- chaos-it: no-nemesis
- full-jepsen: PASS
- command: run-jepsen-nochao.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS; no-nemesis

### 2026-10-05-jepsen-edge-matrix-1dc-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: 770e79c
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-swarm-chaos
- mode: 1dc-swarm-chaos
- outcome: FAIL
- git: 770e79c
- compose: up-swarm
- chaos-it: partition+kill+swarm-bounce
- full-jepsen: FAIL
- command: run-jepsen-swarm.ps1 append (time-limit=60)
- notes: swarm-append=FAIL

### 2026-10-05-jepsen-edge-matrix-1dc-join-shards
- mode: 1dc-join-shards-chaos
- outcome: PASS
- git: 770e79c
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen-join.ps1 join (time-limit=60)
- notes: join-shards=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: 770e79c
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-nochao
- mode: nochao
- outcome: PASS
- git: 770e79c
- compose: up
- chaos-it: no-nemesis
- full-jepsen: PASS
- command: run-jepsen-nochao.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS; no-nemesis

### 2026-10-05-jepsen-edge-matrix-1dc-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: 770e79c
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-swarm-chaos
- mode: 1dc-swarm-chaos
- outcome: PASS
- git: 770e79c
- compose: up-swarm
- chaos-it: partition+kill+swarm-bounce
- full-jepsen: PASS
- command: run-jepsen-swarm.ps1 append (time-limit=60)
- notes: swarm-append=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-join-shards
- mode: 1dc-join-shards-chaos
- outcome: PASS
- git: 770e79c
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen-join.ps1 join (time-limit=60)
- notes: join-shards=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: PASS
- git: c2aca8b
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-chaos
- mode: full-jepsen
- outcome: FAIL
- git: c2aca8b
- compose: up
- chaos-it: partition+kill
- full-jepsen: FAIL
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=FAIL

### 2026-10-05-jepsen-edge-matrix-1dc-nochao
- mode: nochao
- outcome: PASS
- git: c2aca8b
- compose: up
- chaos-it: no-nemesis
- full-jepsen: PASS
- command: run-jepsen-nochao.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS; no-nemesis

### 2026-10-05-jepsen-edge-matrix-1dc-unclean-revive
- mode: full-jepsen
- outcome: FAIL
- git: c2aca8b
- compose: up
- chaos-it: partition+kill
- full-jepsen: FAIL
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=FAIL

### 2026-10-05-jepsen-edge-matrix-1dc-swarm-chaos
- mode: 1dc-swarm-chaos
- outcome: PASS
- git: c2aca8b
- compose: up-swarm
- chaos-it: partition+kill+swarm-bounce
- full-jepsen: PASS
- command: run-jepsen-swarm.ps1 append (time-limit=60)
- notes: swarm-append=PASS

### 2026-10-05-jepsen-edge-matrix-1dc-join-shards
- mode: 1dc-join-shards-chaos
- outcome: PASS
- git: c2aca8b
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen-join.ps1 join (time-limit=60)
- notes: join-shards=PASS

### 2026-10-05-solo-1dc-ok-nil
- mode: full-jepsen
- outcome: PASS
- git: c2aca8b
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-10-05-solo-1dc-ok-nil-verify
- mode: full-jepsen
- outcome: PASS
- git: c2aca8b
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS

### 2026-10-05-solo-1dc-unclean-revive
- mode: full-jepsen
- outcome: PASS
- git: c2aca8b
- compose: up
- chaos-it: partition+kill
- full-jepsen: PASS
- command: run-jepsen.ps1 register+append (time-limit=60)
- notes: register=PASS; append=PASS