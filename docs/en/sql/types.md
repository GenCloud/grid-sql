# Types

The type set is small and deliberately scalar. The canonical list is `org.genfork.grid.catalog.SqlType` from the `grid-commons` module: server and client share the same enum, so the "type in the catalog" and the "type on the wire" can never diverge.

## Mapping table

| DDL tokens | `SqlType` | What the client sees |
|------------|-----------|----------------------|
| `INT`, `INTEGER` | `INT` | `Integer` |
| `BIGINT`, `LONG` | `BIGINT` | `Long` |
| `DOUBLE`, `FLOAT`, `REAL` | `DOUBLE` | `Double` |
| `BOOLEAN`, `BOOL` | `BOOLEAN` | `Boolean` |
| `VARCHAR`, `STRING`, `TEXT`, `JSON` | `VARCHAR` | `String` |
| `TIMESTAMP`, `DATETIME` | `TIMESTAMP` | `String` (ISO-8601) |
| `BYTES`, `BYTEA`, `BINARY`, `BLOB` | `BYTES` | `byte[]` |
| `UUID` | `UUID` | `java.util.UUID` |
| `DATE` | `DATE` | `LocalDate` |
| `TIME` | `TIME` | `LocalTime` |
| `TIMESTAMPTZ`, `TIMESTAMP WITH TIME ZONE` | `TIMESTAMPTZ` | `Instant` |

Tokens are case-insensitive. Several synonyms map to the same `SqlType` — that exists so common schema type names port without rewriting every line.

## Numbers

`INT` is 32-bit, `BIGINT` is 64-bit. `DOUBLE` is double-precision floating point; `FLOAT` and `REAL` land there too, there is no separate single precision.

There is no fixed-point type (`DECIMAL`, `NUMERIC`). For money, use `BIGINT` in minor units — cents, kopecks — and format on the application side. That is more reliable than `DOUBLE` and faster than any decimal arithmetic.

## Strings and JSON

`VARCHAR` has no length limit: length is neither declared nor validated. `STRING` and `TEXT` are synonyms.

`JSON` also collapses to `VARCHAR` and is stored as opaque UTF-8 text. Grid does not parse JSON, does not validate it and cannot search by paths inside a document. If you need to filter on a field, lift that field into its own column.

`JSONB` is rejected with an explicit error: there is no binary JSON with internal structure in the storage layer, and silently substituting text for it would be a lie.

## Date and time

There are four temporal types, and the choice between them matters.

| Type | Representation | When to use |
|------|----------------|-------------|
| `TIMESTAMPTZ` | `Instant`, binary, UTC | A point in time. The preferred choice for new schemas |
| `DATE` | `LocalDate`, binary | A calendar date without time |
| `TIME` | `LocalTime`, binary | A time of day without a date |
| `TIMESTAMP` | ISO-8601 string | Compatibility with older schemas |

`TIMESTAMP` stays text on the wire for compatibility with already sealed rows. It takes more space and compares as a string. Use `TIMESTAMPTZ` in new tables.

Literals are written with a type prefix:

```sql
SELECT * FROM events
  WHERE day = DATE '2026-09-22'
    AND at  >= TIMESTAMPTZ '2026-09-22T10:00:00Z';
```

## UUID

`UUID` is a fixed 16 bytes, not a 36-character string. Comparison and indexing happen on bytes.

```sql
CREATE TABLE sessions (id UUID PRIMARY KEY, user_id BIGINT);
UPSERT INTO sessions (id, user_id) VALUES (UUID '3f2504e0-4f89-11d3-9a0c-0305e82c3301', 42);
```

## Bytes

`BYTES` (also `BYTEA`, `BINARY`, `BLOB`) stores an arbitrary byte array and arrives at the client as `byte[]`. This is the right home for compressed payloads, serialized messages and small images. Filtering on content works only as whole-value equality.

## NULL

Any column without `NOT NULL` accepts `NULL`. Test with `IS NULL` and `IS NOT NULL`; `= NULL` will not do what you want, exactly as in any other SQL.

A column added via `ALTER TABLE ADD COLUMN` reads as `NULL` for previously written rows.

## Casting

```sql
SELECT CAST('42' AS BIGINT) FROM demo WHERE id = 1;
```

`CAST` works on values within the type set listed above. There is no implicit coercion between incompatible types during comparison: compare a column against a value of the same type, or you will get an error rather than a quietly empty result.

| Works | Does not fail silently |
|-------|------------------------|
| `CAST('42' AS BIGINT)`, compatible scalars | `WHERE int_col = '42'` without CAST (type error) |
| Prefixed literals `DATE '…'`, `UUID '…'` | Treating `TIMESTAMP` (string) and `TIMESTAMPTZ` (Instant) as one type |

On the wire and in indexes the key is `SqlType` bytes. On the client (reactive `SqlResult` or JDBC `ResultSet`) you see the Java objects from the mapping table. There is no `ALTER … TYPE`: add a column and migrate in the application.

## What the client sees

Results arrive in `SqlResult` windows. Each column carries metadata: a name and a `SqlType`. Values are delivered as the Java objects in the right-hand column of the table above.

An important internal detail: values become objects **only at the boundary** of the result. Inside the server a row is a binary blob, fields are read by cursor at offsets, and filter, join and index keys stay as bytes. That is why adding a column does not force a data rewrite, and why selecting two columns from a wide table does not decode the whole row.

The JDBC client on top of the same types is described in [JDBC client](../develop/jdbc-tooling.md).

## Next

- [DDL](ddl.md) — how to declare columns and constraints.
- [DML](dml.md) — which literals are allowed in values.
- [Indexes](indexes.md) — which types suit bitmap and which suit a B+ tree.
