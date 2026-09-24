# Документация Grid

Grid — распределённая SQL-база данных: скорость оперативной памяти, надёжное хранение на диске и репликация между узлами по протоколу ORCHID. Приложения подключаются библиотекой `grid-sql-client` по `grid://` (реактивный API) или `jdbc:grid://` (JDBC) — один протокол, два стабильных интерфейса.

Английская версия тех же страниц: [`docs/en/`](../en/). Корень документации: [`../README.md`](../README.md).

## С чего начать

### Если вы здесь впервые

- [Введение](getting-started/what-is-grid.md)
- [Быстрый старт](getting-started/quick-start.md)
- [Запуск кластера](getting-started/start-cluster.md)
- [Чек-лист перед промышленной эксплуатацией](getting-started/production-checklist.md)

### Разработчикам

- [Java-клиент](develop/java-client.md)
- [Примеры для запуска](../../examples/README.ru.md)
- [SQL](sql/fundamentals.md)
- [Транзакции](develop/transactions.md)
- [jOOQ DSL](develop/jooq.md)
- [Возможности](getting-started/features.md)

### Эксплуатация

- [Долговременное хранение](configure-and-operate/configuration/durability.md)
- [Репликация](configure-and-operate/configuration/replication.md)
- [Мониторинг](configure-and-operate/monitoring.md)
- [Отказы](configure-and-operate/operations/failures.md)
- [Безопасность](configure-and-operate/operations/security.md)
- [Резервные копии и восстановление](configure-and-operate/operations/backup-restore.md)
- [Обновление узла](configure-and-operate/operations/upgrade.md)
- [Повышение роли узла](configure-and-operate/operations/ha-promote.md)
- [Чтение с реплики](configure-and-operate/operations/replica-reads.md)
- [Overlay PIN](configure-and-operate/configuration/overlay-pin.md)
- [Восстановление на момент времени (PITR)](configure-and-operate/operations/pitr.md)

### API

- [Справочник](api-reference/README.md)
- [JDBC-клиент](develop/jdbc-tooling.md)

## Разделы

| Раздел | Описание |
|--------|----------|
| [Начало работы](getting-started/what-is-grid.md) | Введение, установка, первый запрос |
| [Разработка](develop/java-client.md) | Клиенты, Spring Boot, транзакции |
| [SQL](sql/fundamentals.md) | Диалект, DDL/DML, индексы, планы |
| [Конфигурация и эксплуатация](configure-and-operate/configuration/sql-server.md) | Настройки узлов, HA, несколько ЦОД |
| [Как устроено](understand/architecture-overview.md) | Архитектура, хранение, ORCHID |
| [Справочник API](api-reference/README.md) | Java SPI |
| [Инструменты](tools/sql-cli.md) | CLI, нагрузка, глоссарий |
| [Производительность](performance/capacity-slo.md) | Измерения и пороги на лабораторном хосте |

### Начало работы

| Страница | |
|----------|--|
| [Введение](getting-started/what-is-grid.md) | |
| [Возможности](getting-started/features.md) | |
| [Зачем Grid](getting-started/positioning.md) | |
| [Быстрый старт](getting-started/quick-start.md) | |
| [Кластер](getting-started/start-cluster.md) | |
| [Подключение](getting-started/connect-clients.md) | |
| [Чек-лист перед промышленной эксплуатацией](getting-started/production-checklist.md) | |
| [Практические советы](getting-started/best-practices.md) | |

### Разработка

| Страница | |
|----------|--|
| [Java-клиент](develop/java-client.md) | |
| [JDBC-клиент](develop/jdbc-tooling.md) | |
| [jOOQ DSL](develop/jooq.md) | |
| [Spring Boot](develop/spring-boot.md) | |
| [Транзакции](develop/transactions.md) | |
| [Потоковая выдача](develop/wire-streaming.md) | |

### SQL

| Страница | |
|----------|--|
| [Основы](sql/fundamentals.md) | |
| [DDL](sql/ddl.md) · [DML](sql/dml.md) · [Типы](sql/types.md) · [Индексы](sql/indexes.md) | |
| [EXPLAIN и AQE](sql/explain-and-aqe.md) | |
| [Матрица поддержки SQL](sql/support-matrix.md) | |

### Конфигурация и эксплуатация

| Страница | |
|----------|--|
| [Долговременное хранение](configure-and-operate/configuration/durability.md) | |
| [Репликация](configure-and-operate/configuration/replication.md) | |
| [SQL-сервер](configure-and-operate/configuration/sql-server.md) | |
| [Закрепление ключей (PIN)](configure-and-operate/configuration/overlay-pin.md) | |
| [Мониторинг](configure-and-operate/monitoring.md) | |
| [Отказы](configure-and-operate/operations/failures.md) | |
| [Безопасность](configure-and-operate/operations/security.md) | |
| [Резервные копии и восстановление](configure-and-operate/operations/backup-restore.md) | |
| [Обновление узла](configure-and-operate/operations/upgrade.md) | |
| [Повышение роли узла](configure-and-operate/operations/ha-promote.md) | |
| [Чтение с реплики](configure-and-operate/operations/replica-reads.md) | |
| [Несколько ЦОД](configure-and-operate/operations/multi-dc.md) | |
| [PITR](configure-and-operate/operations/pitr.md) | |
| [HA под нагрузкой](configure-and-operate/operations/cluster-ha-highload.md) | |
| [Несколько ЦОД под нагрузкой](configure-and-operate/operations/cluster-multidc-highload.md) | |
| [Compose-развёртывание](configure-and-operate/operations/deploy-compose.md) | |

### Как устроено

| Страница | |
|----------|--|
| [Обзор архитектуры](understand/architecture-overview.md) | |
| [Хранение (GMAP)](understand/storage-sealed-gmap.md) | |
| [ORCHID](understand/orchid-consensus.md) | |
| [Конкурентность и видимость данных](understand/concurrency-and-visibility.md) | |
| [Сеть репликации](understand/replication-network.md) | |
| [Путь записи](understand/write-path-staging.md) | |
| [Overlay и размещение](understand/overlay-and-swarm.md) | |
| [Состояние репликации](understand/replication-state.md) | |
| [Биометафоры и границы утверждений](understand/bio-inspired.md) | |

### Производительность

Ориентиры записи, чтения и смешанной нагрузки для лабораторного хоста (два узла, `fsync: true`) — в [ёмкости и порогах](performance/capacity-slo.md). Порты по умолчанию: SQL **15432** / **15433**, репликация **5615** / **5616**.

| Страница | |
|----------|--|
| [Ёмкость и пороги](performance/capacity-slo.md) | |
| [Сводные результаты](performance/results.md) | |
| [Методика](performance/methodology.md) | |
| [Duplex-кодирование](performance/perf-duplex.md) | |
| [Критический путь ORCHID](performance/perf-bio-consensus.md) | |

### Инструменты

| Страница | |
|----------|--|
| [SQL CLI](tools/sql-cli.md) | |
| [JMeter и нагрузка](tools/jmeter-load-slo.md) | |
| [Глоссарий](tools/glossary.md) | |

### Внутреннее

Заметки для участников разработки:

- [Правила разработки](internal/development.md)
- [ORCHID TLA](internal/orchid-tla.md)
- [Журнал дефектов](internal/bug-journal.md)
- [EncodeBuffers](internal/encode-buffers.md)
- [GridFs](internal/grid-fs.md)
