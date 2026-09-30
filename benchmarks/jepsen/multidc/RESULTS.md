# Multi-DC Jepsen RESULTS

Honest PASS/FAIL after Docker+lein (never invent `:valid? true`).

## Latest stamp (ASYNC_SHIP)

| Field | Value |
|-------|--------|
| stamp | `2026-09-30-multidc-unclean-revive-evidence` |
| date | 2026-09-30T11:03:18+03:00 |
| git | unknown |
| host | DESKTOP-4IC511D |
| mode | ASYNC_SHIP (async) |
| outcome | `PASS` |
| register | PASS |
| append | PASS |
| chaos | unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a |
| notes | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=60; register=PASS; append=PASS |

Multi-host SQL URL:

```
grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434,127.0.0.1:15435/public
```

See [README.md](README.md). Coverage: [../COVERAGE.md](../COVERAGE.md). Parent 1-DC: [../RESULTS.md](../RESULTS.md).

## History

| stamp | mode | register | append | outcome | notes |
|-------|------|----------|--------|---------|-------|
| `2026-09-30-multidc-unclean-revive-evidence` | ASYNC_SHIP | PASS | PASS | PASS | FULL lein chaos=unclean-revive+dc-link+kill-voter+kill-dc-a+revive-dc-a time-limit=60; register=PASS; append=PASS |
