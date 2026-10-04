Thanks for opening a pull request against Grid SQL.

Before review, please work through the checklist below. Prefer a short, evidence-backed description: **what** changed and **why**, not a line-by-line walkthrough of **how**.

### Contribution checklist

- [ ] There is a single tracking id for this PR (`G-XXXX` GitHub issue / internal ticket), or `N/A` with a one-line reason (typo, pure docs).
- [ ] The PR description states **what** landed and **why** (symptom, invariant, or gap). Avoid speculative “might help” wording.
- [ ] The PR title is merge-ready: `phase x.y.z` and/or `G-XXXX` plus a short summary.
- [ ] Change notes below use `docs` / `fix` / `features`. Delete unused sections.
- [ ] External contributors: CLA boxes below are checked (see [CLA.md](CLA.md)). Maintainers may skip CLA checkboxes for work already owned by the project.
- [ ] CI on this PR is green, or failures are explained in **Notes** with a link to the run.

### Change notes

<!-- Drop empty blocks. -->

```
phase x.y.z

docs
- …

fix
- …

features
- …
```

**Ticket:** `G-` / `N/A`

**Why (2–4 sentences max):**



### CLA

- [ ] I have read and agree to the Grid SQL Contributor License Agreement ([CLA.md](CLA.md)).
- [ ] I am the sole copyright owner of this Contribution, **or** my employer has accepted the Corporate CLA in `CLA.md`.

### Verification

Mark only what you actually ran. Do not claim calm stamps you did not take.

- [ ] Module tests for the touched surface (`mvn -pl <modules> -am test` — list modules).
- [ ] Focused unit/IT named in this PR (class or path).
- [ ] Hot-path / replication / TX / sealed / OpLog touch → calm re-stamp listed below (QG, Jepsen, Multi-DC, JMH, load — whichever applies); otherwise `N/A`.
- [ ] No new mid-pipeline `Object` decode; no synthetic SQL execute; no `.block()` / commonPool on library paths (`N/A` if docs-only).
- [ ] Sources are UTF-8 without BOM.

**Gates / commands run:**



### Risk and rollback

<!-- What can regress; how to revert or feature-off. `None` is fine for docs-only. -->



### Notes

- Contributing guide: [CONTRIBUTING.md](CONTRIBUTING.md)
- Conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- CLA: [CLA.md](CLA.md)
- Perf / gate order: [docs/en/performance/methodology.md](docs/en/performance/methodology.md)
- Development notes: [docs/en/internal/development.md](docs/en/internal/development.md)

Questions for maintainers: open a GitHub Issue on this repository.