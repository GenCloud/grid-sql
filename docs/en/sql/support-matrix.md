# SQL support matrix

Statement-by-statement list of what the Simplified SQL dialect accepts, with the restriction that applies to each form. The dialect is defined by the `SimplifiedSql.g4` grammar and the typed statement parser: a form that is not listed here fails at parse time, before any row is touched.

Parsing is strict. There is no error recovery and no "best effort" execution of a statement the grammar does not describe.

## Statements

| Group | Accepted forms |
|-------|----------------|
| Query | `SELECT`, `WITH [RECURSIVE] cte AS (…) SELECT`, `UNION [ALL]`, `INTERSECT`, `EXCEPT` |
| Plans | `EXPLAIN <statement>`, `EXPLAIN ANALYZE <statement>`, `ANALYZE <table>` |
| Write | `INSERT`, `UPSERT`, `UPDATE`, `DELETE`, `TRUNCATE TABLE`, `MERGE` |
| Transaction | `BEGIN [TRANSACTION\|WORK]`, `START TRANSACTION`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `ROLLBACK TO [SAVEPOINT] name`, `RELEASE SAVEPOINT` |
| Prepared | `PREPARE name AS <statement>`, `EXECUTE name [USING v, …]`, `DEALLOCATE [PREPARE] name` |
| Tables | `CREATE TABLE [IF NOT EXISTS]`, `DROP TABLE [IF EXISTS]`, `ALTER TABLE … ADD COLUMN [IF NOT EXISTS]`, `ALTER TABLE … ADD [CONSTRAINT c] CHECK (…)`, `ALTER TABLE … ADD [CONSTRAINT c] PRIMARY KEY (…)`, `ALTER TABLE … ADD [CONSTRAINT c] FOREIGN KEY (…) REFERENCES …`, `ALTER TABLE … DROP COLUMN`, `ALTER TABLE … DROP CONSTRAINT` |
| Indexes | `CREATE [UNIQUE\|BITMAP] INDEX [IF NOT EXISTS]`, `DROP INDEX [IF EXISTS] name [ON table]` |
| Schemas | `CREATE SCHEMA [IF NOT EXISTS] [AUTHORIZATION user]`, `DROP SCHEMA [IF EXISTS] name [RESTRICT]`, `SET SCHEMA name` | `AUTHORIZATION` ignored (no owner); `RESTRICT` optional (default); `CASCADE` rejected |
| Views | `CREATE VIEW … AS <query>`, `CREATE MATERIALIZED VIEW … AS <query>`, `REFRESH MATERIALIZED VIEW`, `DROP VIEW [IF EXISTS]` |
| Sequences | `CREATE SEQUENCE [IF NOT EXISTS] s [START WITH n] [INCREMENT BY n] [RECLAIM]`, `DROP SEQUENCE`, `SELECT NEXTVAL('s')`, `SELECT CURRVAL('s')` |
| Functions | `CREATE FUNCTION f(args) RETURNS type AS CLASS 'fqcn' METHOD 'name'`, `DROP FUNCTION` |
| Triggers | `CREATE TRIGGER t (BEFORE\|AFTER) (INSERT\|UPDATE\|DELETE) ON tbl FOR EACH (ROW\|STATEMENT) [WHEN 'expr'] AS 'sql'`, `DROP TRIGGER` |
| Access | `CREATE USER … PASSWORD`, `ALTER USER … PASSWORD`, `DROP USER`, `CREATE ROLE`, `DROP ROLE`, `GRANT`, `REVOKE` |
| Overlay | `PIN KEY table key [TTL ms] [QOS tag]`, `UNPIN KEY table key` |
| Session | `SET REMOTE_DIRTY TRUE\|FALSE` |

DDL inside an open transaction is rejected — the catalog does not take part in rollback. Run schema changes in autocommit, before or after the transaction.

## SELECT

| Clause | Accepted | Restriction |
|--------|----------|-------------|
| Projection | `*`, `col [AS alias]`, `COUNT(*)`, `SUM\|AVG\|MIN\|MAX(col)`, window expression, `fn(args) [AS alias]`, `COALESCE(…)`, `NOW()`, `CURRENT_TIMESTAMP`, `CURRENT_DATE` | `COUNT(col)` and `COUNT(DISTINCT col)` are not accepted; `COALESCE` args are columns or values |
| `DISTINCT` | `SELECT DISTINCT …` | Statement-level only, not `COUNT(DISTINCT …)` |
| `FROM` | One table, or a table function call | No table aliases, no derived tables, no comma joins |
| `JOIN` | `[INNER \| LEFT \| RIGHT \| FULL] [OUTER] JOIN t ON a = b` | Equality of two columns only; one condition per join |
| `WHERE` | Predicate tree, see below | — |
| `GROUP BY` / `HAVING` | Column list; `HAVING` compares an aggregate to a value | No expressions in `GROUP BY` |
| `WINDOW` | `WINDOW w AS (PARTITION BY … ORDER BY …)` | No frame clause (`ROWS`/`RANGE BETWEEN`) |
| `ORDER BY` | `col [ASC\|DESC], …` | Columns only, no expressions or ordinals |
| Paging | `LIMIT n`, `LIMIT offset, count`, `LIMIT n OFFSET m` | — |
| Row locks | `FOR UPDATE`, `FOR UPDATE SKIP LOCKED` | Writer node only; a read replica rejects it |
| No `FROM` | `SELECT fn(args)`, `SELECT NEXTVAL('s')` | Only a function or sequence call — `SELECT 1` is not a valid statement |

Window functions: `ROW_NUMBER()`, `RANK()`, `DENSE_RANK()`, `LAG(col)`, `LEAD(col)`, and `SUM`/`MIN`/`MAX`/`AVG(col)` with `OVER (…)` or `OVER w`.

## Predicates and values

| Element | Accepted |
|---------|----------|
| Comparison | `col op value`, `col op col`, `col op (subquery)`, `fn(args) op value`, `aggregate op value` |
| Operators | `=`, `!=`, `>`, `>=`, `<`, `<=` — `<>` is not an operator here |
| Range and set | `col BETWEEN a AND b`, `col IN (v, …)`, `col IN (subquery)`, `EXISTS (subquery)` |
| Pattern | `col LIKE 'pattern'` |
| Null test | `col IS NULL`, `col IS NOT NULL` |
| Composition | `AND`, `OR`, `NOT`, parentheses, bare `TRUE` / `FALSE` |
| Values | integer, decimal, string, `NULL`, `TRUE`, `FALSE`, `?`, `CAST(value AS type)`, `COALESCE(a, b, …)`, `NOW()`, `CURRENT_TIMESTAMP`, `CURRENT_DATE`, `DATE '…'`, `TIME '…'`, `TIMESTAMP '…'`, `TIMESTAMPTZ '…'`, `UUID '…'`, `CASE WHEN … END`, `NEXTVAL`/`CURRVAL`, `EXCLUDED.col` (ON CONFLICT DO UPDATE only) |

Two consequences worth knowing before you write a filter:

- **No arithmetic in expressions.** `WHERE price * qty > 100` and `SELECT a + b` are not in the grammar. Compute on the application side, or keep the value in its own column.
- **Numeric literals are signed.** A leading minus is part of the grammar (`'-'? INT` / decimal), and binds may supply negative INT/LONG. Wire order for fixed-width INT/LONG index and residual compare is signed (`Integer.compare` / `Long.compare`).
- **`CAST` applies to a value, not a column.** `CAST('42' AS BIGINT)` is accepted; `CAST(col AS BIGINT)` is not.

Positional `?` parameters are materialized into SQL literals before parsing, so a parameter is accepted anywhere a literal of the same shape is accepted — including inside `LIKE` and `IN`.

## Write statements

| Statement | Accepted | Restriction |
|-----------|----------|-------------|
| `INSERT` / `UPSERT` | `VALUES (…), (…)`, optional column list, `ON CONFLICT`, `RETURNING` | `VALUES` tuples only — no `INSERT … SELECT`; omitted columns take column `DEFAULT` when declared |
| `ON CONFLICT` | `DO NOTHING`, `DO UPDATE SET col = value, …` | Values may be literals, `EXCLUDED.col`, `COALESCE(…)`, or clock builtins; the optional column list names the primary key or a unique index |
| `UPDATE` | `SET` assignments, optional `FROM source`, mandatory `WHERE`, optional `RETURNING` | See the assignment table below |
| `DELETE` | Mandatory `WHERE` | No unconditional delete, no `RETURNING` |
| `TRUNCATE TABLE` | Unconditional clear of all rows | Same journal / TX path as DELETE: on failure, reject to the client with no partial visibility |
| `MERGE` | `USING (VALUES (…))` or `USING table`, `ON a = b`, `WHEN MATCHED THEN UPDATE`, `WHEN NOT MATCHED THEN INSERT` | Single source row; `WHEN MATCHED` takes literal assignments only |

`WHERE` is mandatory on `UPDATE` and `DELETE`. Unconditional mass delete uses `TRUNCATE TABLE` (not a bare `DELETE`).

### Assignments in SET

| Form | Meaning |
|------|---------|
| `col = value` | Literal / builtin / `EXCLUDED.col` (ON CONFLICT) |
| `col = col + value` | Numeric read-modify-write |
| `col = col \|\| value` | String concatenation, repeatable: `col \|\| a \|\| b` |
| `col = CONCAT(col, value)` | Same concatenation, function form |

Read-modify-write forms are limited to those three. Subtraction, multiplication and division are not in the grammar — express a decrement as a literal `SET` of the recomputed value inside a transaction, where the record lock protects the read-then-write.

A statement that mixes read-modify-write with literal assignments requires primary-key equality in `WHERE`, and is applied as one merge: a single write of the final row bytes, not two sequential updates. `UPDATE … FROM` accepts literal assignments only and requires exactly one column equality as its condition.

## DDL details

| Element | Accepted | Restriction |
|---------|----------|-------------|
| Column | `col type [NOT NULL] [GENERATED BY DEFAULT AS IDENTITY] [PRIMARY KEY] [DEFAULT lit\|NOW()\|CURRENT_TIMESTAMP\|CURRENT_DATE]`, `col SERIAL\|BIGSERIAL [PRIMARY KEY]` | `DEFAULT` materializes once at INSERT when the column is omitted |
| Primary key | On the column, or `PRIMARY KEY (a, b)` as a table element; `ALTER … ADD PRIMARY KEY (…)` replaces the PK column set | Determines the shard |
| Foreign key | `[CONSTRAINT c] FOREIGN KEY (…) REFERENCES t (…) [ON DELETE act] [ON UPDATE act]` on CREATE or ALTER | Actions: `RESTRICT`, `CASCADE`, `SET NULL`; covering child index is auto-wired |
| Check | `[CONSTRAINT c] CHECK (expression)` | Boolean expression of this dialect |
| Uniqueness | `CREATE UNIQUE INDEX` | There is no column-level `UNIQUE` |
| Bitmap index | `CREATE BITMAP INDEX … (col)` | One column; rejected on several columns and never enabled through configuration |
| `ALTER TABLE` | `ADD COLUMN [IF NOT EXISTS]`, `ADD CHECK`, `ADD PRIMARY KEY`, `ADD FOREIGN KEY`, `DROP COLUMN`, `DROP CONSTRAINT` (FK/CHECK) | No type change, no rename; PRIMARY KEY cannot be dropped alone — replace via `ADD PRIMARY KEY` |
| Privileges | `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `DDL` on a schema or a table | `GRANT ROLE r TO user` for membership; no `REVOKE ROLE` |

Identifiers are unquoted words of ASCII letters, digits and underscore, matched case-insensitively; a name may be qualified as `schema.object`. Double-quoted identifiers are not part of the grammar, so a column cannot be named after a keyword. Strings use single quotes with `''` as the escape. Line (`--`) and block (`/* … */`) comments are skipped by the lexer.

## Cutover note (fork-plus-0)

Prefer `UPSERT` / `INSERT … ON CONFLICT … DO UPDATE SET col = EXCLUDED.col` (optionally with `COALESCE`) over stored procedures. Product path does **not** require SQL `LANGUAGE …` functions or Java `AS CLASS` UDF jars for migration cutover.

## Not in the dialect

| Missing | Alternative |
|---------|-------------|
| `INSERT … SELECT` | `SELECT` on the client, then `UPSERT` with binds |
| Correlated subqueries of arbitrary shape | `IN (subquery)`, `EXISTS (subquery)`, scalar comparison |
| Arbitrary expressions in projection / `WHERE` / `GROUP BY` / `ORDER BY` | `COALESCE` / clock builtins where listed; otherwise compute in the application |
| `DECIMAL` / `NUMERIC` | `BIGINT` in minor units — see [types](types.md) |
| `JSONB`, JSON path search | `JSON` / `VARCHAR` as opaque text; lift searchable fields into columns |
| Arrays, user-defined types | Scalar columns, or `BYTES` for an opaque payload |
| Stored procedures in a procedural language | App-side `UPSERT` / `ON CONFLICT … EXCLUDED`; optional `CREATE FUNCTION … AS CLASS … METHOD …` remains SPI-only |
| Partial and expression indexes | Indexes on columns |
| `ALTER TABLE … ALTER COLUMN TYPE`, renames | Add a column and migrate in the application |
| Foreign SQL wire protocol | `grid://` and `jdbc:grid://` only |

## Related

- [Fundamentals](fundamentals.md) — how a statement travels through the server.
- [DDL](ddl.md) and [DML](dml.md) — the same forms with worked examples.
- [Types](types.md) — the type tokens and what the client receives.
- [Indexes](indexes.md) — index kinds and the lookup path.
