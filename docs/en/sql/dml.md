# DML

Mutations in Grid always end the same way: the server assembles the final row bytes and hands them to the write path. There are no storage-level "append to a field" or "increment a counter" operations — even `SET total = total + 1` is first recomputed on the server and then written as a whole row.

The journal holds two kinds of record: `UPSERT` (the full row) and `DELETE` (the key). Write path details are in [the write path](../understand/write-path-staging.md); the accepted syntax for every form below is in the [support matrix](support-matrix.md).

## INSERT and UPSERT

```sql
INSERT INTO orders (id, customer_id, total) VALUES (1, 100, 49.90);
UPSERT INTO orders (id, customer_id, total) VALUES (1, 100, 59.90);
```

The difference shows up when the key already exists:

| Statement | Key free | Key taken |
|-----------|----------|-----------|
| `INSERT` | Insert | Error (duplicate primary key) |
| `UPSERT` | Insert | Row overwritten with the `VALUES` payload |

Several rows in one statement:

```sql
INSERT INTO orders (id, customer_id, total) VALUES
  (2, 100, 10.00),
  (3, 101, 20.00),
  (4, 101, 30.00);
```

Columns not listed get `NULL` — unless they carry `NOT NULL`, `IDENTITY` or `SERIAL`.

## ON CONFLICT

`UPSERT` is shorthand for "overwrite with the `VALUES` payload". When you need different behaviour, spell the conflict out:

```sql
-- silently skip a duplicate
INSERT INTO orders (id, customer_id) VALUES (1, 100)
  ON CONFLICT DO NOTHING;

-- update only some columns
INSERT INTO orders (id, customer_id, total) VALUES (1, 100, 59.90)
  ON CONFLICT (id) DO UPDATE SET total = 59.90, status = 'updated';
```

The column list in `ON CONFLICT (...)` says which key the conflict is checked against: the primary key or a unique index. Without a list the primary key is used.

`DO UPDATE SET` takes literal assignments only. A conflict resolution that has to read the stored value first — "add the incoming amount to the existing one" — belongs in an `UPDATE` inside a transaction, not in `ON CONFLICT`.

## UPDATE

```sql
UPDATE orders SET status = 'shipped' WHERE id = 1;
UPDATE orders SET status = 'bulk', total = 0 WHERE customer_id = 100;
```

`WHERE` is mandatory. Updating a whole table in one unconditional statement is simply not in the grammar — that is deliberate protection against an accidental "update everything".

The right-hand side of an assignment is not limited to literals. Three read-modify-write forms exist:

```sql
UPDATE orders SET total = total + 10 WHERE id = 1;          -- numeric increment
UPDATE orders SET status = status || '-done' WHERE id = 1;  -- concatenation
UPDATE orders SET status = CONCAT(status, '-done') WHERE id = 1;
-- read-modify-write and a literal in one SET: PK equality in WHERE is required
UPDATE orders SET total = total + 10, status = 'updated' WHERE id = 1;
```

Such forms are evaluated on the server against the current row: the cursor reads the field, the value is recomputed, the row is re-encoded in full. Races are closed by a record lock, not by an optimistic client-side rewrite. Mixing a read-modify-write with literal assignments produces a single merge — one write of the final row bytes, not two sequential updates.

That list is exhaustive: subtraction, multiplication and division are not in the grammar, and numeric literals carry no sign. To decrease a value, read it and assign the result inside a transaction, where the record lock covers the read-then-write:

```sql
BEGIN;
SELECT total FROM orders WHERE id = 1 FOR UPDATE;
UPDATE orders SET total = 39.90 WHERE id = 1;   -- value recomputed by the application
COMMIT;
```

Update with a source from another table — literal assignments only, and the condition is one column equality:

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

`WHERE` is mandatory here too. A delete appends a `DELETE` record for the key to the journal and removes entries from secondary indexes.

If a foreign key references the table, behaviour depends on `ON DELETE` in the [DDL](ddl.md): `RESTRICT` rejects the delete, `CASCADE` deletes child rows, `SET NULL` clears the references.

## MERGE

Single-row `MERGE`: the source is either a `VALUES` tuple or another table, and the condition is equality on the primary or a unique key.

```sql
MERGE INTO orders
  USING (VALUES (1, 100, 49.90))
  ON id = id
  WHEN MATCHED THEN UPDATE SET total = 49.90, status = 'merged'
  WHEN NOT MATCHED THEN INSERT (id, customer_id, total) VALUES (1, 100, 49.90);
```

Both branches are optional: you can keep only `WHEN MATCHED` (update without insert) or only `WHEN NOT MATCHED` (insert without update). `WHEN MATCHED THEN UPDATE SET` takes literal assignments, like `ON CONFLICT`. For a plain row overwrite, `UPSERT` is shorter and cheaper.

## RETURNING

`INSERT`, `UPSERT` and `UPDATE` can return a result set instead of a plain row count:

```sql
UPSERT INTO orders (id, customer_id, total) VALUES (5, 102, 15.00)
  RETURNING id, total;

UPDATE orders SET status = 'shipped' WHERE id = 5
  RETURNING *;
```

This is the primary way to learn a value generated by `SERIAL` or `IDENTITY`.

## Parameters and prepared statements

Positional parameters use `?`:

```sql
UPSERT INTO orders (id, customer_id, total) VALUES (?, ?, ?);
```

A named prepared statement lives for the duration of the session:

```sql
PREPARE upsert_order AS
  UPSERT INTO orders (id, customer_id, total) VALUES (?, ?, ?);

EXECUTE upsert_order USING 6, 103, 12.50;

DEALLOCATE PREPARE upsert_order;
```

The `PREPARE` body is parsed by the same grammar as any other statement — you can prepare more than just `SELECT`.

## SELECT: what is available

Reads use the same dialect. In brief:

| Capability | Form |
|------------|------|
| Filters | `=`, `!=`, `>`, `>=`, `<`, `<=`, `BETWEEN`, `IN`, `LIKE`, `IS NULL`, `IS NOT NULL` |
| Joins | `JOIN`, `LEFT`/`RIGHT`/`FULL OUTER`, `INNER`; the condition is column equality |
| Aggregates | `COUNT(*)`, `SUM`, `AVG`, `MIN`, `MAX` with `GROUP BY` and `HAVING` |
| Sorting and slicing | `ORDER BY ... ASC/DESC`, `LIMIT n`, `LIMIT n OFFSET m`, plus `LIMIT offset, count` |
| Set operations | `UNION`, `UNION ALL`, `INTERSECT`, `EXCEPT` |
| Subqueries | `IN (SELECT ...)`, `EXISTS (SELECT ...)`, comparison against a scalar subquery |
| CTE | `WITH name AS (...) SELECT ...`, including `RECURSIVE` |
| Window functions | `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG`, `LEAD`, aggregates with `OVER (PARTITION BY ... ORDER BY ...)` |
| Row locking | `FOR UPDATE`, `FOR UPDATE SKIP LOCKED` |
| Expressions | `CASE WHEN ... THEN ... ELSE ... END`, `CAST(x AS TYPE)`, function calls |

There are no expressions in projections, filters or `ORDER BY`, and no table aliases in `FROM`. For "neighbouring row" or "previous value" problems, reach for `WITH` plus `LAG`/`LEAD` rather than nested derived tables: the plan comes out shorter and more predictable.

`FOR UPDATE` and `SKIP LOCKED` work on the writer node only. They are meaningless on a read replica — see [replica reads](../configure-and-operate/operations/replica-reads.md).

## Transactions

Without an explicit `BEGIN`, every statement is its own transaction (autocommit).

```sql
BEGIN;
SELECT balance FROM accounts WHERE id = 1 FOR UPDATE;
UPDATE accounts SET balance = 900 WHERE id = 1;            -- debited value from the application
UPDATE accounts SET balance = balance + 100 WHERE id = 2;  -- credit as an increment
COMMIT;
```

Things worth remembering:

- **Dirty until `COMMIT`.** Changes made in an open transaction are visible only to it. Other sessions do not see them; they reach the shared map and the journal at `COMMIT` time.
- **`ROLLBACK` discards everything.** No partially applied transaction is left behind.
- **DDL inside a transaction is rejected.** `CREATE TABLE`, `ALTER TABLE`, `CREATE INDEX` and friends belong outside an open transaction.
- **Savepoints exist:** `SAVEPOINT s1`, `ROLLBACK TO SAVEPOINT s1`, `RELEASE SAVEPOINT s1`.
- **Parallel transactions** are several `TxContext` instances on one connection, not several sockets.

The client-side model is described in [transactions](../develop/transactions.md).

## Next

- [Support matrix](support-matrix.md) — accepted statement forms and their restrictions.
- [Types](types.md) — which literals and values are allowed.
- [EXPLAIN and AQE](explain-and-aqe.md) — how to inspect a plan before running load.
- [The write path](../understand/write-path-staging.md) — what happens to a row after `COMMIT`.
- [Capacity and SLO](../performance/capacity-slo.md) — measured write and read throughput.
