# SQL support matrix

Statement-by-statement list of what the Simplified SQL dialect accepts, with the restriction that applies to each form. The dialect is defined by the `SimplifiedSql.g4` grammar and the typed statement parser: a form that is not listed here fails at parse time, before any row is touched.

Parsing is strict. There is no error recovery and no "best effort" execution of a statement the grammar does not describe.

## Statements

| Group | Accepted forms |
|-------|----------------|
| Query | `SELECT`, `WITH [RECURSIVE] cte AS (…) SELECT`, `UNION [ALL]`, `INTERSECT`, `EXCEPT` |
| Plans | `EXPLAIN <statement>`, `EXPLAIN ANALYZE <statement>`, `ANALYZE <table>` |
| Write | `INSERT`, `UPSERT`, `UPDATE`, `DELETE`, `MERGE` |
| Transaction | `BEGIN [TRANSACTION\|WORK]`, `START TRANSACTION`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `ROLLBACK TO [SAVEPOINT] name`, `RELEASE SAVEPOINT` |
| Prepared | `PREPARE name AS <statement>`, `EXECUTE name [USING v, …]`, `DEALLOCATE [PREPARE] name` |
| Tables | `CREATE TABLE [IF NOT EXISTS]`, `DROP TABLE [IF EXISTS]`, `ALTER TABLE … ADD COLUMN`, `ALTER TABLE … ADD [CONSTRAINT c] CHECK (…)`, `ALTER TABLE … DROP COLUMN` |
| Indexes | `CREATE INDEX`, `CREATE UNIQUE INDEX`, `CREATE BITMAP INDEX`, `DROP INDEX name [ON table]` |
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
| Projection | `*`, `col [AS alias]`, `COUNT(*)`, `SUM\|AVG\|MIN\|MAX(col)`, window expression, `fn(args) [AS alias]` | No expressions in the projection; `COUNT(col)` and `COUNT(DISTINCT col)` are not accepted |
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
| Values | integer, decimal, string, `NULL`, `TRUE`, `FALSE`, `?`, `CAST(value AS type)`, `DATE '…'`, `TIME '…'`, `TIMESTAMP '…'`, `TIMESTAMPTZ '…'`, `UUID '…'`, `CASE WHEN … END`, `NEXTVAL`/`CURRVAL` |

Two consequences worth knowing before you write a filter:

- **No arithmetic in expressions.** `WHERE price * qty > 100` and `SELECT a + b` are not in the grammar. Compute on the application side, or keep the value in its own column.
- **Numeric literals are unsigned.** A leading minus is not part of the grammar, so a negative number cannot be written as a literal or supplied as a bind. Keep signed values out of SQL text: store magnitude and direction separately, or compute the final value in the application and assign it with a plain `SET col = ?`.
- **`CAST` applies to a value, not a column.** `CAST('42' AS BIGINT)` is accepted; `CAST(col AS BIGINT)` is not.

Positional `?` parameters are materialized into SQL literals before parsing, so a parameter is accepted anywhere a literal of the same shape is accepted — including inside `LIKE` and `IN`.

## Write statements

| Statement | Accepted | Restriction |
|-----------|----------|-------------|
| `INSERT` / `UPSERT` | `VALUES (…), (…)`, optional column list, `ON CONFLICT`, `RETURNING` | `VALUES` tuples only — no `INSERT … SELECT` |
| `ON CONFLICT` | `DO NOTHING`, `DO UPDATE SET col = value, …` | Literal assignments only; the optional column list names the primary key or a unique index |
| `UPDATE` | `SET` assignments, optional `FROM source`, mandatory `WHERE`, optional `RETURNING` | See the assignment table below |
| `DELETE` | Mandatory `WHERE` | No unconditional delete, no `RETURNING` |
| `MERGE` | `USING (VALUES (…))` or `USING table`, `ON a = b`, `WHEN MATCHED THEN UPDATE`, `WHEN NOT MATCHED THEN INSERT` | Single source row; `WHEN MATCHED` takes literal assignments only |

`WHERE` is mandatory on `UPDATE` and `DELETE`. That is grammar, not configuration: an unconditional mass mutation cannot be expressed.

### Assignments in SET

| Form | Meaning |
|------|---------|
| `col = value` | Literal assignment (including `?`) |
| `col = col + value` | Numeric read-modify-write |
| `col = col \|\| value` | String concatenation, repeatable: `col \|\| a \|\| b` |
| `col = CONCAT(col, value)` | Same concatenation, function form |

Read-modify-write forms are limited to those three. Subtraction, multiplication and division are not in the grammar — express a decrement as a literal `SET` of the recomputed value inside a transaction, where the record lock protects the read-then-write.

A statement that mixes read-modify-write with literal assignments requires primary-key equality in `WHERE`, and is applied as one merge: a single write of the final row bytes, not two sequential updates. `UPDATE … FROM` accepts literal assignments only and requires exactly one column equality as its condition.

## DDL details

| Element | Accepted | Restriction |
|---------|----------|-------------|
| Column | `col type [NOT NULL] [GENERATED BY DEFAULT AS IDENTITY] [PRIMARY KEY]`, `col SERIAL\|BIGSERIAL [PRIMARY KEY]` | No `DEFAULT <expression>` |
| Primary key | On the column, or `PRIMARY KEY (a, b)` as a table element | Determines the shard |
| Foreign key | `[CONSTRAINT c] FOREIGN KEY (…) REFERENCES t (…) [ON DELETE act] [ON UPDATE act]` | Actions: `RESTRICT`, `CASCADE`, `SET NULL` |
| Check | `[CONSTRAINT c] CHECK (expression)` | Boolean expression of this dialect |
| Uniqueness | `CREATE UNIQUE INDEX` | There is no column-level `UNIQUE` |
| Bitmap index | `CREATE BITMAP INDEX … (col)` | One column; rejected on several columns and never enabled through configuration |
| `ALTER TABLE` | `ADD COLUMN`, `ADD CHECK`, `DROP COLUMN` | No type change, no rename, no `DROP CONSTRAINT` |
| Privileges | `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `DDL` on a schema or a table | `GRANT ROLE r TO user` for membership; no `REVOKE ROLE` |

Identifiers are unquoted words of ASCII letters, digits and underscore, matched case-insensitively; a name may be qualified as `schema.object`. Double-quoted identifiers are not part of the grammar, so a column cannot be named after a keyword. Strings use single quotes with `''` as the escape.

## Not in the dialect

| Missing | Alternative |
|---------|-------------|
| `INSERT … SELECT` | `SELECT` on the client, then `UPSERT` with binds |
| Correlated subqueries of arbitrary shape | `IN (subquery)`, `EXISTS (subquery)`, scalar comparison |
| Expressions in projection, `WHERE`, `GROUP BY`, `ORDER BY` | Compute in the application, or materialize a column |
| `DECIMAL` / `NUMERIC` | `BIGINT` in minor units — see [types](types.md) |
| `JSONB`, JSON path search | `JSON` / `VARCHAR` as opaque text; lift searchable fields into columns |
| Arrays, user-defined types | Scalar columns, or `BYTES` for an opaque payload |
| Stored procedures in a procedural language | `CREATE FUNCTION … AS CLASS … METHOD …` |
| Partial and expression indexes | Indexes on columns |
| `ALTER TABLE … ALTER COLUMN TYPE`, renames | Add a column and migrate in the application |
| Foreign SQL wire protocol | `grid://` and `jdbc:grid://` only |

## Related

- [Fundamentals](fundamentals.md) — how a statement travels through the server.
- [DDL](ddl.md) and [DML](dml.md) — the same forms with worked examples.
- [Types](types.md) — the type tokens and what the client receives.
- [Indexes](indexes.md) — index kinds and the lookup path.
