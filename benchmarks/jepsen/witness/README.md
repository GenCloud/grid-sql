# Witness Jepsen stamp

Hold+Hold+Witness claim quorum recipe for Multi-DC region fencing.

**Stamp status: PASS** — `2026-09-21-witness-chaos` (register+append, kill-dc-a). See [RESULTS.md](RESULTS.md).

## Artifacts

| Artifact | Purpose |
|----------|---------|
| [configs/application-w1.yml](configs/application-w1.yml) | Witness voter YAML (`region.role: WITNESS`, peers wired, `quorum-size: 2`) |
| [docker-compose.witness-overlay.yml](docker-compose.witness-overlay.yml) | Compose overlay adding `w1`; health `/health/liveness` |
| [RESULTS.md](RESULTS.md) | Honest stamp |
| [scripts/run-witness-check.ps1](scripts/run-witness-check.ps1) | Preconditions |
| [scripts/run-witness-chaos.ps1](scripts/run-witness-chaos.ps1) | Full register+append chaos with Witness overlay |

## Recipe

```powershell
powershell -File .\benchmarks\jepsen\witness\scripts\run-witness-chaos.ps1 -TimeLimit 60
```

Bake: `Dockerfile` / `Dockerfile.witness-overlay` → `/app/config/witness/`.

Ops: [docs/en/configure-and-operate/operations/ha-promote.md](../../../docs/en/configure-and-operate/operations/ha-promote.md).
