# Введение

Grid — распределённая SQL-база данных. Схема и данные описываются на SQL, горячие строки отдаются из оперативной памяти, а при включённом долговременном хранении каждое изменение сначала попадает в журнал изменений (*OpLog*) и в запечатанные файлы карты (*sealed* `.gmap` / `.sbpt`) и только потом становится видимым для читателей. Между узлами запись допускается протоколом согласия ORCHID: строка появляется в выдаче после записи в журнал и совпадения контрольных сумм (*digest*) на кворуме узлов.

Самый короткий путь к работающему узлу — профиль `capacity`: собрать starter, поднять один узел с записью на диск, выполнить DDL и DML по `grid://`, а при необходимости добавить пару `primary` / `replica`. Пошагово: [быстрый старт](quick-start.md).

## Что входит в продукт

| Слой | Роль |
|------|------|
| SQL и каталог | DDL/DML, Simplified SQL (ANTLR), таблицы без обязательного Java-класса предметной области |
| Память | Рабочий набор (`GridScalableMap` и индексы) — ускоритель, а не единственный источник истины при долговременном хранении |
| Диск | Журнал OpLog и запечатанные файлы GMAP; восстановление после перезапуска |
| Кластер | ORCHID, репликация поверх Netty, передача роли пишущего узла через `ServerMeta` / `PROMOTE_NOTIFY` |
| Клиент | `grid://` (реактивный API) или `jdbc:grid://` (JDBC) в `grid-sql-client` |

Долговременное хранение (`grid.durability.enabled`) и репликация (`grid.replication.enabled`) — **независимые** переключатели. Одиночный узел с записью на диск и без пиров (*peers*, соседние узлы) — штатный поддерживаемый режим.

Полный перечень возможностей диалекта и кластера: [возможности](features.md). Обоснование архитектуры: [зачем Grid](positioning.md).

## Типичные сценарии

| Цель | С чего начать | Затем |
|------|---------------|-------|
| Локально проверить SQL | [быстрый старт](quick-start.md) | [подключение клиентов](connect-clients.md) |
| Один durable-узел навсегда | [долговременное хранение](../configure-and-operate/configuration/durability.md) | [резервные копии](../configure-and-operate/operations/backup-restore.md) |
| Primary + replica | [запуск кластера](start-cluster.md) | [повышение роли](../configure-and-operate/operations/ha-promote.md) |
| Ввод в эксплуатацию | [чек-лист](production-checklist.md) | [мониторинг](../configure-and-operate/monitoring.md) |
| Две площадки | [несколько ЦОД](../configure-and-operate/operations/multi-dc.md) | [отказы](../configure-and-operate/operations/failures.md) |
| Понять видимость и TX | [параллелизм и видимость](../understand/concurrency-and-visibility.md) | [транзакции](../develop/transactions.md) |
| Узнать границы диалекта | [матрица SQL](../sql/support-matrix.md) | [DDL](../sql/ddl.md) / [DML](../sql/dml.md) |
| Инцидент | [отказы](../configure-and-operate/operations/failures.md) | [PITR](../configure-and-operate/operations/pitr.md) |
| Схема в IDE | [JDBC-клиент](../develop/jdbc-tooling.md) | [безопасность](../configure-and-operate/operations/security.md) |

## Как устроена запись (кратко)

1. Клиент шлёт SQL по TCP (порт демо **15432**).
2. При durability / репликации: допуск ORCHID → запись в OpLog (`fsync` на промышленных профилях) → карта в памяти и очереди индексов.
3. Только после этого строка видна другим сессиям как зафиксированная.
4. Реплики подтягивают журнал; чтение с отстающей реплики не форсируют.

Подробнее: [путь записи](../understand/write-path-staging.md), [обзор архитектуры](../understand/architecture-overview.md).

## Клиент

Приложения зависят только от `grid-sql-client` и подключаются по `grid://` (реактивный API) либо `jdbc:grid://` (JDBC) — один протокол, два интерфейса. Одно TCP-соединение несёт множество логических транзакций, их предел задаёт `maxTxContexts`. Подробнее: [подключение клиентов](connect-clients.md), [JDBC-клиент](../develop/jdbc-tooling.md).

## Границы

- Сервисы работают только по `grid://` или `jdbc:grid://` (кадры Grid).
- Один `dataDir` на узел на локальном диске — не NFS/SAN на весь кластер.
- Один пишущий; смена роли через `PROMOTE_NOTIFY` / `rediscoverWriter()`, не перебором хостов в URL.
- В памяти — рабочий набор; при durability источник истины — sealed и OpLog.
- TLS на SQL-порту нет — терминация перед узлом ([безопасность](../configure-and-operate/operations/security.md)).

## Ориентиры ёмкости

Плановые значения записи, чтения и смешанной нагрузки для лабораторного хоста (два узла, `fsync: true`) и условия измерения приведены в [ёмкости и порогах](../performance/capacity-slo.md). Порты демонстрационных профилей: SQL **15432** / **15433**, репликация **5615** / **5616**.

**Связанное:** [обзор архитектуры](../understand/architecture-overview.md), [долговременное хранение](../configure-and-operate/configuration/durability.md), [репликация](../configure-and-operate/configuration/replication.md), [отказы](../configure-and-operate/operations/failures.md).