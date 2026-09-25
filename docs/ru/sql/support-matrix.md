# Поддерживаемые конструкции SQL

Перечень форм, которые принимает диалект Simplified SQL, с ограничением для каждой. Диалект задан грамматикой `SimplifiedSql.g4` и типизированным разбором операторов: форма, которой нет в этом перечне, отклоняется на этапе разбора, до обращения к данным.

Разбор строгий: восстановления после ошибки нет, «примерного» исполнения непонятного оператора тоже.

## Операторы

| Группа | Что принимается |
|--------|-----------------|
| Выборка | `SELECT`, `WITH [RECURSIVE] cte AS (…) SELECT`, `UNION [ALL]`, `INTERSECT`, `EXCEPT` |
| Планы | `EXPLAIN <оператор>`, `EXPLAIN ANALYZE <оператор>`, `ANALYZE <таблица>` |
| Запись | `INSERT`, `UPSERT`, `UPDATE`, `DELETE`, `TRUNCATE TABLE`, `MERGE` |
| Транзакции | `BEGIN [TRANSACTION\|WORK]`, `START TRANSACTION`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `ROLLBACK TO [SAVEPOINT] имя`, `RELEASE SAVEPOINT` |
| Подготовленные | `PREPARE имя AS <оператор>`, `EXECUTE имя [USING v, …]`, `DEALLOCATE [PREPARE] имя` |
| Таблицы | `CREATE TABLE [IF NOT EXISTS]`, `DROP TABLE [IF EXISTS]`, `ALTER TABLE … ADD COLUMN [IF NOT EXISTS]`, `ALTER TABLE … ADD [CONSTRAINT c] CHECK (…)`, `ALTER TABLE … ADD [CONSTRAINT c] PRIMARY KEY (…)`, `ALTER TABLE … ADD [CONSTRAINT c] FOREIGN KEY (…) REFERENCES …`, `ALTER TABLE … DROP COLUMN`, `ALTER TABLE … DROP CONSTRAINT` |
| Индексы | `CREATE [UNIQUE\|BITMAP] INDEX [IF NOT EXISTS]`, `DROP INDEX [IF EXISTS] имя [ON таблица]` |
| Схемы | `CREATE SCHEMA [IF NOT EXISTS] [AUTHORIZATION user]`, `DROP SCHEMA [IF EXISTS] имя [RESTRICT]`, `SET SCHEMA имя` | `AUTHORIZATION` игнорируется; `RESTRICT` опционален (по умолчанию); `CASCADE` отклоняется |
| Представления | `CREATE VIEW … AS <запрос>`, `CREATE MATERIALIZED VIEW … AS <запрос>`, `REFRESH MATERIALIZED VIEW`, `DROP VIEW [IF EXISTS]` |
| Последовательности | `CREATE SEQUENCE [IF NOT EXISTS] s [START WITH n] [INCREMENT BY n] [RECLAIM]`, `DROP SEQUENCE`, `SELECT NEXTVAL('s')`, `SELECT CURRVAL('s')` |
| Функции | `CREATE FUNCTION f(аргументы) RETURNS тип AS CLASS 'fqcn' METHOD 'имя'`, `DROP FUNCTION` |
| Триггеры | `CREATE TRIGGER t (BEFORE\|AFTER) (INSERT\|UPDATE\|DELETE) ON таблица FOR EACH (ROW\|STATEMENT) [WHEN 'выражение'] AS 'sql'`, `DROP TRIGGER` |
| Доступ | `CREATE USER … PASSWORD`, `ALTER USER … PASSWORD`, `DROP USER`, `CREATE ROLE`, `DROP ROLE`, `GRANT`, `REVOKE` |
| Наложение | `PIN KEY таблица ключ [TTL мс] [QOS метка]`, `UNPIN KEY таблица ключ` |
| Сессия | `SET REMOTE_DIRTY TRUE\|FALSE` |

DDL внутри открытой транзакции отклоняется: каталог не участвует в откате. Схему меняйте в autocommit — до или после транзакции.

## SELECT

| Часть | Что принимается | Ограничение |
|-------|-----------------|-------------|
| Список выборки | `*`, `колонка [AS псевдоним]`, `COUNT(*)`, `SUM\|AVG\|MIN\|MAX(колонка)`, оконное выражение, `fn(аргументы) [AS псевдоним]`, `COALESCE(…)`, `NOW()`, `CURRENT_TIMESTAMP`, `CURRENT_DATE` | `COUNT(колонка)` / `COUNT(DISTINCT …)` нет; аргументы `COALESCE` — колонки или значения |
| `DISTINCT` | `SELECT DISTINCT …` | Только на уровне оператора, не `COUNT(DISTINCT …)` |
| `FROM` | Одна таблица либо вызов табличной функции | Псевдонимов таблиц, производных таблиц и соединения через запятую нет |
| `JOIN` | `[INNER \| LEFT \| RIGHT \| FULL] [OUTER] JOIN t ON a = b` | Только равенство двух колонок, одно условие на соединение |
| `WHERE` | Дерево предикатов, см. ниже | — |
| `GROUP BY` / `HAVING` | Список колонок; `HAVING` сравнивает агрегат со значением | Выражений в `GROUP BY` нет |
| `WINDOW` | `WINDOW w AS (PARTITION BY … ORDER BY …)` | Рамки окна (`ROWS`/`RANGE BETWEEN`) нет |
| Сортировка | `колонка [ASC\|DESC], …` | Только колонки: ни выражений, ни номеров |
| Срез | `LIMIT n`, `LIMIT смещение, количество`, `LIMIT n OFFSET m` | — |
| Блокировка строк | `FOR UPDATE`, `FOR UPDATE SKIP LOCKED` | Только на пишущем узле; реплика для чтения такой запрос отклоняет |
| Без `FROM` | `SELECT fn(аргументы)`, `SELECT NEXTVAL('s')` | Только вызов функции или последовательности — `SELECT 1` оператором не является |

Оконные функции: `ROW_NUMBER()`, `RANK()`, `DENSE_RANK()`, `LAG(колонка)`, `LEAD(колонка)`, а также `SUM`/`MIN`/`MAX`/`AVG(колонка)` с `OVER (…)` или `OVER w`.

## Предикаты и значения

| Элемент | Что принимается |
|---------|-----------------|
| Сравнение | `колонка оп значение`, `колонка оп колонка`, `колонка оп (подзапрос)`, `fn(аргументы) оп значение`, `агрегат оп значение` |
| Операторы | `=`, `!=`, `>`, `>=`, `<`, `<=` — оператора `<>` здесь нет |
| Диапазон и множество | `колонка BETWEEN a AND b`, `колонка IN (v, …)`, `колонка IN (подзапрос)`, `EXISTS (подзапрос)` |
| Шаблон | `колонка LIKE 'шаблон'` |
| Проверка на NULL | `колонка IS NULL`, `колонка IS NOT NULL` |
| Составление | `AND`, `OR`, `NOT`, скобки, отдельные `TRUE` / `FALSE` |
| Значения | целое, дробное, строка, `NULL`, `TRUE`, `FALSE`, `?`, `CAST(значение AS тип)`, `COALESCE(a, b, …)`, `NOW()`, `CURRENT_TIMESTAMP`, `CURRENT_DATE`, `DATE '…'`, `TIME '…'`, `TIMESTAMP '…'`, `TIMESTAMPTZ '…'`, `UUID '…'`, `CASE WHEN … END`, `NEXTVAL`/`CURRVAL`, `EXCLUDED.колонка` (только в ON CONFLICT DO UPDATE) |

Три следствия, о которых стоит знать до того, как писать фильтр:

- **Арифметики в выражениях нет.** `WHERE price * qty > 100` и `SELECT a + b` грамматикой не описаны. Считайте на стороне приложения или храните значение в отдельной колонке.
- **Числовые литералы без знака.** Ведущего минуса в грамматике нет, поэтому отрицательное число нельзя ни написать литералом, ни передать параметром. Держите знак вне текста SQL: храните величину и направление отдельно либо считайте итоговое значение в приложении и присваивайте обычным `SET колонка = ?`.
- **`CAST` применяется к значению, а не к колонке.** `CAST('42' AS BIGINT)` принимается, `CAST(колонка AS BIGINT)` — нет.

Позиционные параметры `?` подставляются в текст SQL литералами до разбора, поэтому параметр допустим везде, где допустим литерал того же вида, — включая `LIKE` и `IN`.

## Операторы записи

| Оператор | Что принимается | Ограничение |
|----------|-----------------|-------------|
| `INSERT` / `UPSERT` | `VALUES (…), (…)`, необязательный список колонок, `ON CONFLICT`, `RETURNING` | Только кортежи `VALUES`; пропущенные колонки берут `DEFAULT`, если он объявлен |
| `ON CONFLICT` | `DO NOTHING`, `DO UPDATE SET колонка = значение, …` | Значения: литералы, `EXCLUDED.колонка`, `COALESCE(…)`, clock-builtin; список колонок — PK или уникальный индекс |
| `UPDATE` | Присваивания `SET`, необязательный `FROM`, обязательный `WHERE`, необязательный `RETURNING` | См. таблицу присваиваний ниже |
| `DELETE` | Обязательный `WHERE` | Удаления без условия нет, `RETURNING` тоже |
| `TRUNCATE TABLE` | Полная очистка таблицы | Fail-closed путь OpLog / TX (как у DELETE) |
| `MERGE` | `USING (VALUES (…))` или `USING таблица`, `ON a = b`, `WHEN MATCHED THEN UPDATE`, `WHEN NOT MATCHED THEN INSERT` | Одна строка источника; в `WHEN MATCHED` — только литеральные присваивания |

`WHERE` в `UPDATE` и `DELETE` обязателен. Безусловное массовое удаление — через `TRUNCATE TABLE`.

### Присваивания в SET

| Форма | Смысл |
|-------|-------|
| `колонка = значение` | Литерал / builtin / `EXCLUDED.колонка` (ON CONFLICT) |
| `колонка = колонка + значение` | Числовое чтение-изменение-запись |
| `колонка = колонка \|\| значение` | Склейка строк, можно подряд: `колонка \|\| a \|\| b` |
| `колонка = CONCAT(колонка, значение)` | Та же склейка в виде функции |

Форм чтения-изменения-записи ровно три. Вычитания, умножения и деления в грамматике нет: уменьшение значения пишется литеральным `SET` с уже пересчитанным значением внутри транзакции, где чтение и запись защищены блокировкой записи.

Оператор, смешивающий чтение-изменение-запись с литеральными присваиваниями, требует равенства по первичному ключу в `WHERE` и применяется одним слиянием: одна запись финальных байтов строки, а не два последовательных `UPDATE`. `UPDATE … FROM` принимает только литеральные присваивания и требует ровно одного равенства колонок в условии.

## Подробности DDL

| Элемент | Что принимается | Ограничение |
|---------|-----------------|-------------|
| Колонка | `колонка тип [NOT NULL] [GENERATED BY DEFAULT AS IDENTITY] [PRIMARY KEY] [DEFAULT lit\|NOW()\|CURRENT_TIMESTAMP\|CURRENT_DATE]`, `колонка SERIAL\|BIGSERIAL [PRIMARY KEY]` | `DEFAULT` материализуется один раз при INSERT, если колонка опущена |
| Первичный ключ | На колонке либо `PRIMARY KEY (a, b)`; `ALTER … ADD PRIMARY KEY (…)` заменяет набор PK | Определяет шард |
| Внешний ключ | `[CONSTRAINT c] FOREIGN KEY (…) REFERENCES t (…)` в CREATE или ALTER | Действия: `RESTRICT`, `CASCADE`, `SET NULL`; покрывающий индекс на child — автоматически |
| Проверка | `[CONSTRAINT c] CHECK (выражение)` | Булево выражение этого же диалекта |
| Уникальность | `CREATE UNIQUE INDEX` | `UNIQUE` на колонке нет |
| Битовый индекс | `CREATE BITMAP INDEX … (колонка)` | Одна колонка; на нескольких отклоняется, через конфигурацию не включается |
| `ALTER TABLE` | `ADD COLUMN [IF NOT EXISTS]`, `ADD CHECK`, `ADD PRIMARY KEY`, `ADD FOREIGN KEY`, `DROP COLUMN`, `DROP CONSTRAINT` (FK/CHECK) | Нет смены типа и переименования; PK отдельно не сбрасывается — замена через `ADD PRIMARY KEY` |
| Привилегии | `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `DDL` на схему или таблицу | Членство — `GRANT ROLE r TO пользователь`; команды `REVOKE ROLE` нет |

Идентификаторы — слова без кавычек из латинских букв, цифр и подчёркивания, регистр не важен; имя может быть квалифицированным: `схема.объект`. Идентификаторов в двойных кавычках грамматика не знает. Строки — в одинарных кавычках, экранирование удвоением: `''`. Комментарии `--` и `/* … */` пропускает лексер.

## Cutover (fork-plus-0)

Для миграций предпочитайте `UPSERT` / `INSERT … ON CONFLICT … DO UPDATE SET col = EXCLUDED.col` (при необходимости с `COALESCE`), а не хранимые процедуры. Product path cutover **не** требует SQL `LANGUAGE …` функций и Java `AS CLASS` / jar UDF.

## Чего в диалекте нет

| Нет | Чем заменить |
|-----|--------------|
| `INSERT … SELECT` | `SELECT` на клиенте, затем `UPSERT` с параметрами |
| Коррелированных подзапросов произвольной формы | `IN (подзапрос)`, `EXISTS (подзапрос)`, сравнение со скалярным подзапросом |
| Произвольных выражений в выборке / `WHERE` / `GROUP BY` / `ORDER BY` | `COALESCE` / clock, где перечислены; иначе расчёт в приложении |
| `DECIMAL` / `NUMERIC` | `BIGINT` в минимальных единицах — см. [типы](types.md) |
| `JSONB`, поиска по JSON-путям | `JSON` / `VARCHAR` как непрозрачный текст; искомые поля — в колонки |
| Массивов и пользовательских типов | Скалярные колонки либо `BYTES` для непрозрачной полезной нагрузки |
| Хранимых процедур на процедурном языке | App-side `UPSERT` / `ON CONFLICT … EXCLUDED`; `CREATE FUNCTION … AS CLASS … METHOD …` остаётся SPI-only |
| Частичных индексов и индексов по выражению | Индексы по колонкам |
| `ALTER TABLE … ALTER COLUMN TYPE`, переименования | Новая колонка и перенос данных приложением |
| Чужого SQL-протокола | Только `grid://` и `jdbc:grid://` |

## Связанное

- [Основы](fundamentals.md) — как оператор проходит через сервер.
- [DDL](ddl.md) и [DML](dml.md) — те же формы с примерами.
- [Типы](types.md) — токены типов и что получает клиент.
- [Индексы](indexes.md) — виды индексов и путь поиска.
