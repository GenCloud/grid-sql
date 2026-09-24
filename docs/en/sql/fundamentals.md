# SQL fundamentals

Grid speaks **Simplified SQL** — a deliberately bounded dialect that covers key-value access and analytics on the same data: schema, row writes, filtered reads and joins, window functions, transactions, indexes. It is not a full SQL clone of any other engine.

Parsing goes **through ANTLR only**, from the `SimplifiedSql.g4` grammar to a typed statement. There is no hand-rolled keyword matching in the engine core, and no error recovery: a statement the grammar does not describe is rejected before any row is touched, instead of being partially executed.

The exact accepted surface, form by form, is in the [support matrix](support-matrix.md).

## How a statement travels through the server

| Step | What happens | Classes |
|------|--------------|---------|
| 1. Intake | The client sends SQL text and parameters over `grid://` | `RemoteConnectionFactory`, `SqlServer` |
| 2. Parse | ANTLR grammar → typed `Stmt` | `SimplifiedSql.g4`, `SqlStatementParser` |
| 3. Catalog | Table and column names are validated against the schema | `TableCatalog`, `TableSchema` |
| 4. Plan | Filters collapse into a condition tree, a scan strategy is picked | `QueryOptimizer`, `query.plan.strategy` |
| 5. Execute | Reads from memory and sealed files, mutations into the staged write path | `SqlEngine`, `TableStore` |
| 6. Deliver | Results are handed back in windows | `SqlResult` |

Inside the server a row is a binary blob, not a Java object. Fields are read by `LogicalFieldCursor` at offsets, and join, filter and index keys stay as bytes all the way to the result boundary. That is why Grid needs no entity classes: the schema lives in the catalog, never in application code.

## What to expect from the dialect

| Area | Behaviour |
|------|-----------|
| Compatibility | A SQL subset. Not a full dialect of another engine |
| DDL | Tables, columns, constraints, indexes, schemas, sequences, views, functions, triggers, users and roles |
| DML | `INSERT`, `UPSERT`, `UPDATE`, `DELETE`, `MERGE`, `ON CONFLICT`, `RETURNING` |
| SELECT | Filters, `JOIN … ON a = b`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`/`OFFSET`, `DISTINCT`, `UNION`/`INTERSECT`/`EXCEPT`, `WITH`, window functions |
| Transactions | `BEGIN`/`COMMIT`/`ROLLBACK`, `SAVEPOINT`. Changes stay private until `COMMIT`. DDL inside an open transaction is rejected |
| Prepared statements | `PREPARE name AS <statement>` / `EXECUTE` / `DEALLOCATE`, with positional `?` parameters |
| Indexes | `CREATE INDEX`, `CREATE UNIQUE INDEX`, `CREATE BITMAP INDEX` — bitmap only by that explicit statement |
| EXPLAIN | `EXPLAIN` and `EXPLAIN ANALYZE`, produced by `SqlExplainService` |
| Types | A scalar `SqlType` set: no user-defined types, no arrays |

Three limits shape most of the day-to-day surface: expressions are not evaluated in projections or filters, `WHERE` is mandatory on `UPDATE` and `DELETE`, and identifiers are unquoted words. The rest is in the [support matrix](support-matrix.md).

### PREPARE

The `PREPARE` body is taken from the original SQL text with spacing intact, not from whitespace-stripped tokens. Parameters are positional `?` only. The name lives in the session (`TxContext` or JDBC connection): another TCP session does not see it. `DEALLOCATE` drops the name, and a disconnect drops every prepare of that session.

## Your first statements

Schema, one row, a read:

```sql
CREATE TABLE IF NOT EXISTS demo (
  id     BIGINT PRIMARY KEY,
  name   VARCHAR,
  active BOOLEAN
);

UPSERT INTO demo (id, name, active) VALUES (1, 'first', true);

SELECT id, name FROM demo WHERE id = 1;
```

Parameters instead of literals:

```sql
SELECT id, name FROM demo WHERE active = ? AND id > ?;
```

What the planner intends to do:

```sql
EXPLAIN SELECT id, name FROM demo WHERE active = true ORDER BY id LIMIT 100;
```

Several statements under one transaction:

```sql
BEGIN;
UPDATE demo SET name = 'second' WHERE id = 1;
UPSERT INTO demo (id, name, active) VALUES (2, 'third', false);
COMMIT;
```

Before `COMMIT` only this transaction sees the changes. `ROLLBACK` discards them entirely.

## Where to run it

| Entry point | Use |
|-------------|-----|
| [SQL CLI](../tools/sql-cli.md) | Interactive session against a live node |
| [Java client](../develop/java-client.md) | Reactive application path, `grid://user:pass@127.0.0.1:15432/public` |
| [JDBC client](../develop/jdbc-tooling.md) | Synchronous services, DBeaver and IDE tooling, `jdbc:grid://` |

## Next

| Page | About |
|------|-------|
| [Support matrix](support-matrix.md) | Accepted forms and their restrictions, statement by statement |
| [DDL](ddl.md) | Tables, constraints, schema changes |
| [DML](dml.md) | Insert, update, delete, `MERGE`, transactions |
| [Types](types.md) | Type tokens, `SqlType`, what the client sees |
| [Indexes](indexes.md) | LAX, STRICT, BITMAP and the index lookup path |
| [EXPLAIN and AQE](explain-and-aqe.md) | Plans, parallel scan, partitioned map |
| [Transactions](../develop/transactions.md) | The transaction model from the client side |
| [Capacity and SLO](../performance/capacity-slo.md) | Measured throughput on the lab host |
