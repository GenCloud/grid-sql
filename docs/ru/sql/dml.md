# DML

Мутации в Grid всегда заканчиваются одинаково: сервер собирает финальные байты строки и кладёт их в путь записи. Отдельных операций «дописать поле» или «увеличить счётчик» на уровне хранилища нет — даже `SET total = total + 1` сначала пересчитывается на сервере, а потом записывается целая строка.

Два вида записей в журнале: `UPSERT` (строка целиком) и `DELETE` (ключ). Подробности — [путь записи](../understand/write-path-staging.md); точный синтаксис каждой формы ниже — в [поддерживаемых конструкциях](support-matrix.md).

## INSERT и UPSERT

```sql
INSERT INTO orders (id, customer_id, total) VALUES (1, 100, 49.90);
UPSERT INTO orders (id, customer_id, total) VALUES (1, 100, 59.90);
```

Разница в поведении при существующем ключе:

| Оператор | Ключ свободен | Ключ занят |
|----------|---------------|------------|
| `INSERT` | Вставка | Ошибка (дубликат первичного ключа) |
| `UPSERT` | Вставка | Перезапись строки значениями из `VALUES` |

Несколько строк одним оператором:

```sql
INSERT INTO orders (id, customer_id, total) VALUES
  (2, 100, 10.00),
  (3, 101, 20.00),
  (4, 101, 30.00);
```

Колонки, не перечисленные в списке, получают объявленный `DEFAULT` (литерал / `NOW()` / `CURRENT_TIMESTAMP` / `CURRENT_DATE`), иначе `NULL` — если у них нет `NOT NULL`, `IDENTITY` или `SERIAL`.

### INSERT … SELECT

Строки можно брать из `SELECT` вместо `VALUES`. Тот же путь apply-row / conflict / TX, что и у multi-row `VALUES`. Источники с set-op (`UNION` / …) в SELECT отклоняются.

```sql
INSERT INTO orders_archive (id, customer_id, total)
  SELECT id, customer_id, total FROM orders WHERE status = 'closed';

INSERT INTO orders (id, customer_id, total)
  SELECT id, customer_id, total FROM staging
  ON CONFLICT (id) DO UPDATE SET total = EXCLUDED.total;
```

## ON CONFLICT

`UPSERT` — короткая запись для «перезаписать значениями из `VALUES` / SELECT». Когда нужно другое поведение, пишите конфликт явно:

```sql
-- молча пропустить дубликат
INSERT INTO orders (id, customer_id) VALUES (1, 100)
  ON CONFLICT DO NOTHING;

-- обновить часть колонок (литералы, EXCLUDED, COALESCE, CASE)
INSERT INTO orders (id, customer_id, total) VALUES (1, 100, 59.90)
  ON CONFLICT (id) DO UPDATE SET
    total = EXCLUDED.total,
    status = COALESCE(EXCLUDED.status, orders.status);
```

Список колонок в `ON CONFLICT (...)` указывает, по какому ключу проверяется конфликт: первичный ключ или уникальный индекс. Без списка берётся первичный ключ.

В `DO UPDATE SET` допустимы литералы (в т.ч. `NULL` / `TRUE` / `FALSE` / `?`), `EXCLUDED.col`, builtins, `COALESCE(…)`, row-aware `CASE`. Чистый read-modify-write по сохранённой строке (`total = total + 10`) — через обычный `UPDATE`, не через `ON CONFLICT`.

## UPDATE

```sql
UPDATE orders SET status = 'shipped' WHERE id = 1;
UPDATE orders SET status = 'bulk', total = 0 WHERE customer_id = 100;
UPDATE orders SET note = NULL, active = FALSE WHERE id = 1;
UPDATE orders SET status = status WHERE id = 1;                 -- копирование колонки
UPDATE orders SET flag = CASE WHEN total > 0 THEN TRUE ELSE FALSE END WHERE id = 1;
```

`WHERE` обязателен. Обновление всей таблицы одним оператором без условия грамматикой не предусмотрено — это защита от случайного «обновить всё».

`UPDATE` **не создаёт** отсутствующую строку по PK: нет совпадения — 0 затронутых строк, без empty seed в карте/журнале. Вставка или перезапись целиком — через `UPSERT` / `INSERT … ON CONFLICT`.

Правая часть присваивания: литерал (`NULL` / `TRUE` / `FALSE` / `?` / builtin), другая колонка, `CASE` или форма «прочитать, изменить, записать». Форм RMW ровно три:

```sql
UPDATE orders SET total = total + 10 WHERE id = 1;          -- числовое приращение
UPDATE orders SET status = status || '-done' WHERE id = 1;  -- склейка строк
UPDATE orders SET status = CONCAT(status, '-done') WHERE id = 1;
-- чтение-изменение-запись вместе с литералом: нужен WHERE по равенству первичного ключа
UPDATE orders SET total = total + 10, status = 'updated' WHERE id = 1;
```

Такие формы считаются на сервере поверх текущей строки: курсор читает поле, значение пересчитывается, строка перекодируется целиком. Гонки закрывает блокировка записи, а не оптимистичная перезапись клиентом. Смесь чтения-изменения-записи с литеральными присваиваниями даёт одно слияние — одну запись финальных байтов строки, а не два последовательных `UPDATE`.

Список исчерпывающий: вычитания, умножения и деления в грамматике нет. Числовые литералы **со знаком** (`-10` допустим); bind тоже может передать отрицательный INT/LONG. Чтобы уменьшить значение относительно текущего, можно `SET total = total + (-10)` или прочитать и присвоить внутри транзакции:

```sql
BEGIN;
SELECT total FROM orders WHERE id = 1 FOR UPDATE;
UPDATE orders SET total = 39.90 WHERE id = 1;   -- значение пересчитало приложение
COMMIT;
```

Обновление с источником из другой таблицы — только литеральные присваивания, условие — одно равенство колонок:

```sql
UPDATE orders SET status = 'synced'
  FROM order_sync
  WHERE orders.id = order_sync.order_id;
```

## DELETE

```sql
DELETE FROM orders WHERE id = 1;
DELETE FROM orders WHERE customer_id = 100 AND status = 'draft';
```

`WHERE` обязателен и здесь. Удаление ставит в журнал запись `DELETE` по ключу и убирает записи из вторичных индексов.

Если на таблицу ссылается внешний ключ, поведение зависит от `ON DELETE` в [DDL](ddl.md): `RESTRICT` отклонит удаление, `CASCADE` удалит дочерние строки, `SET NULL` обнулит ссылки.

## TRUNCATE

```sql
TRUNCATE TABLE orders;
```

Безусловная очистка всех строк. `WHERE` здесь нет — в отличие от `DELETE`, где условие обязательно. Путь тот же, что у массового удаления через журнал: при сбое — отказ клиенту, без частичной видимости. Подробнее: [матрица SQL](support-matrix.md).

## MERGE

Однострочный `MERGE`: источником служит либо кортеж `VALUES`, либо другая таблица, условие — равенство по первичному или уникальному ключу.

```sql
MERGE INTO orders
  USING (VALUES (1, 100, 49.90))
  ON id = id
  WHEN MATCHED THEN UPDATE SET total = 49.90, status = 'merged'
  WHEN NOT MATCHED THEN INSERT (id, customer_id, total) VALUES (1, 100, 49.90);
```

Обе ветки необязательны: можно оставить только `WHEN MATCHED` (обновление без вставки) или только `WHEN NOT MATCHED` (вставка без обновления). В `WHEN MATCHED THEN UPDATE SET`, как и в `ON CONFLICT`, допустимы только литеральные присваивания. Для простой перезаписи строки `UPSERT` короче и дешевле.

## RETURNING

`INSERT`, `UPSERT` и `UPDATE` умеют возвращать результат вместо простого счётчика строк:

```sql
UPSERT INTO orders (id, customer_id, total) VALUES (5, 102, 15.00)
  RETURNING id, total;

UPDATE orders SET status = 'shipped' WHERE id = 5
  RETURNING *;
```

Это основной способ узнать значение, сгенерированное `SERIAL` или `IDENTITY`.

## Параметры и подготовленные операторы

Позиционные параметры — знак `?`:

```sql
UPSERT INTO orders (id, customer_id, total) VALUES (?, ?, ?);
```

Именованный подготовленный оператор действует в рамках сессии:

```sql
PREPARE upsert_order AS
  UPSERT INTO orders (id, customer_id, total) VALUES (?, ?, ?);

EXECUTE upsert_order USING 6, 103, 12.50;

DEALLOCATE PREPARE upsert_order;
```

Тело `PREPARE` разбирается той же грамматикой, что и обычный оператор, — подготовить можно не только `SELECT`.

## SELECT: что доступно

Чтение относится к тому же диалекту. Кратко:

| Возможность | Форма |
|-------------|-------|
| Фильтры | `=`, `!=`, `>`, `>=`, `<`, `<=`, `BETWEEN`, `IN`, `LIKE`, `IS NULL`, `IS NOT NULL` |
| Соединения | `JOIN`, `LEFT`/`RIGHT`/`FULL OUTER`, `INNER`; условие — равенство колонок |
| Агрегаты | `COUNT(*)`, `SUM`, `AVG`, `MIN`, `MAX` с `GROUP BY` и `HAVING` |
| Сортировка и срез | `ORDER BY ... ASC/DESC`, `LIMIT n`, `LIMIT n OFFSET m`, а также `LIMIT offset, count` |
| Множества | `UNION`, `UNION ALL`, `INTERSECT`, `EXCEPT` |
| Подзапросы | `IN (SELECT ...)`, `EXISTS (SELECT ...)`, сравнение со скалярным подзапросом |
| Без `FROM` | `SELECT 1`, `SELECT fn(…)`, `SELECT NEXTVAL('s')`, `SELECT EXISTS (SELECT …)`, `SELECT NOT EXISTS (SELECT …)` |
| CTE | `WITH name AS (...) SELECT ...`, в том числе `RECURSIVE` |
| Оконные функции | `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG`, `LEAD`, агрегаты с `OVER (PARTITION BY ... ORDER BY ...)` |
| Блокировка строк | `FOR UPDATE`, `FOR UPDATE SKIP LOCKED` |
| Выражения | `CASE WHEN ... THEN ... ELSE ... END`, `CAST(x AS TYPE)`, вызовы функций; кавычные идентификаторы (`"Col"`) сворачиваются без учёта регистра |

JOOQ `SELECT 1 AS one` внутри `EXISTS` принимается. Для «соседней записи» предпочитайте `WITH` плюс `LAG`/`LEAD`, а не вложенные производные таблицы.

`FOR UPDATE` и `SKIP LOCKED` работают только на узле-писателе. На реплике для чтения они смысла не имеют — см. [чтение с реплики](../configure-and-operate/operations/replica-reads.md).

## Транзакции

Без явного `BEGIN` каждый оператор — своя транзакция (autocommit).

```sql
BEGIN;
SELECT balance FROM accounts WHERE id = 1 FOR UPDATE;
UPDATE accounts SET balance = 900 WHERE id = 1;            -- списание посчитало приложение
UPDATE accounts SET balance = balance + 100 WHERE id = 2;  -- зачисление приращением
COMMIT;
```

Что важно помнить:

- **Грязно до `COMMIT`.** Изменения открытой транзакции видит только она сама. Другие сессии их не видят, в общую карту и журнал они попадают в момент `COMMIT`.
- **`ROLLBACK` отменяет всё.** Частично применённой транзакции не остаётся.
- **DDL внутри транзакции отклоняется.** `CREATE TABLE`, `ALTER TABLE`, `CREATE INDEX` и прочее — только вне открытой транзакции.
- **Точки сохранения есть:** `SAVEPOINT s1`, `ROLLBACK TO SAVEPOINT s1`, `RELEASE SAVEPOINT s1`.
- **Параллельные транзакции** — это несколько `TxContext` на одном соединении, а не несколько сокетов.

Клиентская сторона модели описана в [транзакциях](../develop/transactions.md).

## Дальше

- [Поддерживаемые конструкции](support-matrix.md) — принимаемые формы операторов и ограничения.
- [Типы](types.md) — какие литералы и значения допустимы.
- [EXPLAIN и AQE](explain-and-aqe.md) — как посмотреть план перед тем, как гонять нагрузку.
- [Путь записи](../understand/write-path-staging.md) — что происходит со строкой после `COMMIT`.
- [Ёмкость и пороги](../performance/capacity-slo.md) — измеренная пропускная способность записи и чтения.
