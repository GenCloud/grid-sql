# Введение

Нужна SQL-база, в которой горячие строки отдаются из памяти, а каждое изменение при включённой записи на диск сначала попадает в журнал и только потом становится видимым. Между узлами запись допускает ORCHID: совпали контрольные суммы на большинстве из конфигурации — строка видна; нет — клиент получает отказ. Лучше отказ, чем два журнала.

Самый короткий путь к работающему узлу — профиль `capacity`: собрать starter, поднять один узел с диском, выполнить DDL/DML по `grid://`, при необходимости добавить пару `primary` / `replica`. Пошагово: [быстрый старт](quick-start.md).

## Что входит в продукт

| Слой | Роль |
|------|------|
| SQL и каталог | DDL/DML, Simplified SQL (ANTLR), таблицы без обязательного Java-класса предметной области |
| Память | Горячий набор — ускоритель, не единственный источник истины при записи на диск |
| Диск | Журнал OpLog и запечатанные файлы; восстановление после перезапуска |
| Кластер | ORCHID, репликация по Netty, передача роли пишущего через `ServerMeta` / `PROMOTE_NOTIFY` |
| Клиент | `grid://` (реактивный) или `jdbc:grid://` (JDBC) в `grid-sql-client` |

Долговременное хранение (`grid.durability.enabled`) и репликация (`grid.replication.enabled`) — **независимые** рычаги. Один узел с диском и без соседних узлов — штатный режим.

Полный перечень: [возможности](features.md). Зачем так устроено: [зачем Grid](positioning.md).

## С чего начать

| Цель | Сначала | Затем |
|------|---------|-------|
| Локально проверить SQL | [быстрый старт](quick-start.md) | [подключение клиентов](connect-clients.md) |
| Один узел с диском навсегда | [долговременное хранение](../configure-and-operate/configuration/durability.md) | [резервные копии](../configure-and-operate/operations/backup-restore.md) |
| Primary + replica | [запуск кластера](start-cluster.md) | [повышение роли](../configure-and-operate/operations/ha-promote.md) |
| Ввод в эксплуатацию | [чек-лист](production-checklist.md) | [мониторинг](../configure-and-operate/monitoring.md) |
| Две площадки | [несколько ЦОД](../configure-and-operate/operations/multi-dc.md) | [отказы](../configure-and-operate/operations/failures.md) |
| Видимость и TX | [параллелизм и видимость](../understand/concurrency-and-visibility.md) | [транзакции](../develop/transactions.md) |
| Границы диалекта | [матрица SQL](../sql/support-matrix.md) | [DDL](../sql/ddl.md) / [DML](../sql/dml.md) |
| Инцидент | [отказы](../configure-and-operate/operations/failures.md) | [PITR](../configure-and-operate/operations/pitr.md) |
| Схема в IDE | [JDBC-клиент](../develop/jdbc-tooling.md) | [безопасность](../configure-and-operate/operations/security.md) |

## Как устроена запись

1. Клиент шлёт SQL по TCP (порт демо **15432**).
2. При записи на диск / репликации: допуск ORCHID → журнал OpLog (`fsync` на рабочих профилях) → карта в памяти и индексы.
3. Только после этого строка видна другим сессиям как зафиксированная.
4. Соседние узлы подтягивают журнал; чтение с отстающей реплики не форсируют.

Подробнее: [путь записи](../understand/write-path-staging.md), [обзор архитектуры](../understand/architecture-overview.md).

## Клиент

Приложения зависят от `grid-sql-client` и ходят по `grid://` или `jdbc:grid://` — один протокол, два интерфейса. Одно TCP несёт много логических транзакций: в URL клиента `maxTxContexts` по умолчанию **256**, на сервере жёсткий потолок канала **8** (Boot не поднимает из YAML) — при исчерпании откройте ещё один `Connection`. См. [подключение клиентов](connect-clients.md), [JDBC-клиент](../develop/jdbc-tooling.md).

## Чего ждать нельзя

- Сервисы работают только по `grid://` / `jdbc:grid://`.
- Один `dataDir` на узел на локальном диске — не NFS/SAN на весь кластер.
- Один пишущий; смена роли через `PROMOTE_NOTIFY` / `rediscoverWriter()`, не перебором хостов в URL.
- В памяти — горячий набор; при записи на диск истина — запечатанные файлы и OpLog.
- TLS на SQL-порту нет — терминация снаружи ([безопасность](../configure-and-operate/operations/security.md)).

## Ориентиры ёмкости

Цифры записи, чтения и смеси на лабораторном хосте (два узла, `fsync: true`): [ёмкость и пороги](../performance/capacity-slo.md). Порты демо: SQL **15432** / **15433**, репликация **5615** / **5616**.

Дальше: [обзор архитектуры](../understand/architecture-overview.md), [долговременное хранение](../configure-and-operate/configuration/durability.md), [репликация](../configure-and-operate/configuration/replication.md).
