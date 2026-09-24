# Deploy compose (внутренние HA-лабы) — RU

Отказоустойчивые кластеры Grid SQL для локальной / лабораторной работы.

**Product-образ:** `jamoa-grid-sql:local` из **`grid-sql-server-starter`** (не Jepsen chaos-образ).

Настройки выровнены с живыми гейтами Jepsen / HA Load / Multidc.

EN: [README.md](README.md) · Docs: [docs/ru/configure-and-operate/operations/deploy-compose.md](../../docs/ru/configure-and-operate/operations/deploy-compose.md)

## Топологии

| Путь | Топология | Когда |
|------|-----------|-------|
| `1dc-n2/` | primary + replica (N=2) | JMeter Load / living WRITE·READ |
| `1dc-n3/` | N=3 ORCHID | sticky promote, повседневный HA |
| `multidc-async/` | 3+2+w1 ASYNC_SHIP | cross-DC RPO |
| `multidc-sync/` | 3+2+w1 SYNC_VOTERS | cross-DC стоимость коммита |

Сборка: `powershell -File .\examples\scripts\build-sql-image.ps1`