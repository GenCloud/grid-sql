# Развёртывание через Compose (внутренний HA)

Лабораторные docker-compose топологии отказоустойчивого Grid SQL. Это **стенд для отладки и эталонных knobs HA/multi-DC**, не шаблон продакшена: порты, кворум и образы подобраны под один хост. Дерево: [`examples/compose/`](../../../../examples/compose/).

На одном ноутбуке кворум и RTT не похожи на прод: цифры с compose цитируйте как лабораторные, а не как заявленный максимум кластера. Для боевой топологии смотрите [HA](cluster-ha-highload.md) и [несколько ЦОД](multi-dc.md).

## Рабочий образ (не Jepsen)

Стенды используют **`jamoa-grid-sql:local`**, собранный из модуля **`grid-sql-server-starter`**.

Не используйте здесь `jamoa-grid-jepsen:local` — тот образ только для стендов хаоса / `Elle` / `Knossos` (`benchmarks/jepsen/`).

```powershell
powershell -File .\examples\scripts\build-sql-image.ps1
# Fat jar: grid-sql-server-starter/target/grid-sql-server-starter-1.0-SNAPSHOT.jar
# Dockerfile: examples/docker/Dockerfile
```

Переопределение: `GRID_IMAGE=my-registry/grid-sql:tag` в env-файле или в оболочке.

## Эталонные knobs HA / multi-DC

YAML под каждой топологией совпадает с живыми ориентирами Jepsen / HA Load / Multidc:

| Параметр | Значение | Источник |
|----------|----------|----------|
| orchid order-threshold / digest-quorum | `0.85` / `MAJORITY` | Jepsen + primary |
| orchid max-propose-in-flight | `64` | HA Load primary/replica |
| durability + op-log fsync | включены | продукт + Multidc Jepsen |
| health validate-group-membership | `false` | Jepsen + primary |
| 1dc-n2 hydrate | LAZY | HA Load primary/replica |
| все HA-стенды: swarm, placement, auto-cutover | on / on / true | HA Load + длительная проверка |
| 1dc-n3 / Multidc swarm | on + apply-auto-cutover | HA Load / длительная проверка |
| Multidc witness w1 | порты 15437 / 7782 / 5620 | Jepsen Multidc |

## Конфиги узлов (bind-mount)

У каждой топологии — настоящие Spring YAML в `config/`, монтируются в `/app/config`:

| Топология | Файлы |
|-----------|-------|
| `1dc-n2` | `config/application-primary.yml`, `application-replica.yml` |
| `1dc-n3` | `config/application-n1.yml`, `n2`, `n3` |
| `multidc-async` | `config/application-a1.yml` … `b2.yml`, `w1.yml` (`ASYNC_SHIP`) |
| `multidc-sync` | те же имена (`SYNC_VOTERS_ACROSS_DC`, voters `[b1]`) + `w1.yml` |

Compose ссылается на них явно:

```yaml
command:
  - --spring.config.additional-location=file:/app/config/application-n2.yml
volumes:
  - ./config:/app/config:ro
```

Пиры, порты и `cluster-id` правятся в этих файлах — они и задают стенд.

## Топологии

| Compose | Топология | Когда применять | Схема |
|---------|-----------|-----------------|-------|
| `1dc-n2` | N=2 primary + replica | JMeter Load / WRITE·READ | [запуск кластера](../../getting-started/start-cluster.md) |
| `1dc-n3` | N=3 один ЦОД | повседневный HA, смена writer | [HA в одном ЦОД](cluster-ha-highload.md) |
| `multidc-async` | 3+2+w1 ASYNC_SHIP | RPO / отставание доставки между ЦОД | [несколько ЦОД — ASYNC](cluster-multidc-highload.md) |
| `multidc-sync` | 3+2+w1 SYNC_VOTERS | стоимость коммита cross-DC | [несколько ЦОД — SYNC](cluster-multidc-highload.md) |

Закрепление клиента и узел-свидетель (Witness — участвует в кворуме смены роли, сам не пишет и не читает приложение): [повышение роли узла](ha-promote.md).

Уровни нагрузки `low` / `mid` / `high` меняют только heap JVM через `env/*.env`.

```powershell
cd examples\compose\1dc-n2
docker compose --env-file env\mid.env up -d
# Writer: grid://@127.0.0.1:15432/public
# Reads:  grid://@127.0.0.1:15432/public?readEndpoints=127.0.0.1:15433

cd examples\compose\1dc-n3
docker compose --env-file env\high.env up -d
# SQL: grid://@127.0.0.1:15432,127.0.0.1:15433,127.0.0.1:15434/public
# Готовность: http://127.0.0.1:7777/actuator/health/readiness
```

ASYNC и SYNC Multidc делят host-порты — один стек за раз; не пересекайте с Jepsen Multidc.

## Рестарт OpLog без purge

Убийство процесса посреди записи в журнал оставляет нулевую `length` в хвосте. Сервер **обрезает порванный хвост** и поднимается на целых записях.

Корректный SIGTERM / Spring destroy / shutdown hook JVM: force mmap + атомарный `.wpos`. **SIGKILL / SIGSEGV** хуки не вызывают — следующий open идёт через replay порванного хвоста.

`ORCHID reject non-contiguous commit expected=N got=M` под хаосом — **ожидаемо** (пир отстаёт → HomologousRepair).

## Проверки согласованности (отдельно от compose)

Не гоняйте проверки согласованности, внутренние JMH и JMeter параллельно на одной машине. Порядок: [методика](../../performance/methodology.md).

```powershell
# Дымовая проверка согласованности (стенд Jepsen):
powershell -File .\scripts\run-jepsen-smoke.ps1
```

```powershell
powershell -File .\benchmarks\jepsen\scripts\run-jepsen.ps1 -Fast -TimeLimit 30
# Внутренние JMH-треки — отдельно, не вместе с нагрузкой (см. методику)
```
