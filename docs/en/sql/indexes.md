# Indexes

Secondary indexes are declared in SQL and hold pointers into the encoded row fields. An index stores neither copies of the values nor the rows themselves, only references: the key is looked up in the index, the row is fetched from storage.

## Creating

```sql
CREATE INDEX idx_t_name ON t (name);                 -- plain
CREATE INDEX idx_t_a_b ON t (a, b);                  -- composite
CREATE UNIQUE INDEX uq_t_email ON t (email);         -- with a uniqueness check
CREATE BITMAP INDEX idx_t_flag ON t (flag);          -- explicit command only

DROP INDEX idx_t_name ON t;
```

## Three kinds

| Kind | `IndexType` | Structure | Meaning |
|------|-------------|-----------|---------|
| Plain | `LAX` | `GridPointerBPTree` or `GridPointerCompositeBPTree` | Duplicate values are allowed |
| Unique | `STRICT` | The same tree plus a uniqueness check | A repeated value is rejected on write |
| Bitmap | `BITMAP` | `GridBitmapIndex` inside `GridCompositeIndex` | Low-cardinality column |

`LAX` and `STRICT` differ only in how they treat duplicates. This is not a "strict SQL mode" and not an isolation level — do not confuse it with the `STRICT` keyword from other databases.

### LAX — the default

A plain `CREATE INDEX` gives `LAX`. Values may repeat, and one index key maps to a set of rows. This is the right choice for columns such as `customer_id`, `status_code`, `created_at`.

### STRICT — uniqueness

`CREATE UNIQUE INDEX` gives `STRICT`. A write that violates uniqueness is rejected. Such an index doubles as the target for `ON CONFLICT (...)` and as the match condition in `MERGE`.

### BITMAP — explicit command only

A bitmap index is enabled **exclusively** by the `CREATE BITMAP INDEX` statement. It cannot be turned on through YAML configuration and will never appear on its own: the system does not silently change index structure under your data.

Limits and scope:

- **One column only.** `CREATE BITMAP INDEX ... (a, b)` is rejected.
- Makes sense at **low cardinality** — dozens of distinct values: flags, statuses, regions, event types.
- On a high-cardinality column (identifiers, timestamps, email) a bitmap loses to a B+ tree both in memory and in speed.

```sql
CREATE BITMAP INDEX idx_orders_status ON orders (status);   -- 5 statuses: appropriate
CREATE BITMAP INDEX idx_orders_id ON orders (id);           -- pointless, use a plain index
```

## Composite indexes

Column order matters. An index on `(a, b)` helps queries filtering on `a`, and on `a` together with `b`. A query on `b` alone will not use it.

```sql
CREATE INDEX idx_orders_cust_status ON orders (customer_id, status);

SELECT * FROM orders WHERE customer_id = 100;                      -- index works
SELECT * FROM orders WHERE customer_id = 100 AND status = 'new';   -- index works
SELECT * FROM orders WHERE status = 'new';                         -- index does not fit
```

Put the column you always filter on first.

### Worked example

```sql
CREATE TABLE orders (
  id BIGINT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_orders_cust_status ON orders (customer_id, status);

-- Uses the composite prefix:
SELECT id FROM orders WHERE customer_id = 42 AND status = 'NEW' LIMIT 50;
-- Does not use idx_orders_cust_status (leading column missing):
SELECT id FROM orders WHERE status = 'NEW' LIMIT 50;
```

Check the plan with `EXPLAIN` — [EXPLAIN and AQE](explain-and-aqe.md).

## Primary key

There is no need to index the primary key separately. It determines the shard. Equality on the **full** primary key is a point read — no scan and no secondary index lookup. Equality on only the leading column of a composite PK is not “the whole key”: the planner does not rewrite it into a full-PK get.

## Which index the plan picks

| Condition | What to expect |
|-----------|----------------|
| EQ on the full PK | Point read by key |
| EQ on the leading column of a composite secondary index `(a, b)` | Prefix path on `(a, …)`; if a separate single-column / bitmap index on `a` exists and there is no `ORDER BY` on `b`, the plan may prefer the narrow index |
| EQ only on a non-leading column | Composite `(a, b)` does not apply |
| `ORDER BY` on the second composite column with EQ on the first | Keeps the ordered composite leaf |

Fixed-width INT/LONG compare in the index and in residual filters is **signed** (`Integer.compare` / `Long.compare`), matching signed literals in the grammar. Details: [SQL support matrix](support-matrix.md).

Check the plan with `EXPLAIN` — [EXPLAIN and AQE](explain-and-aqe.md).

## Query path

An index lookup goes through three levels, top down:

1. **In-memory index** — the hot-set accelerator.
2. **Sealed `.sbpt` tree** — an immutable fixed-page B+ tree on disk (written as **VERSION 2**, signed INT/LONG order). **VERSION 1** (legacy unsigned) files are **rejected** on read — reseal / `dumpDomain` after upgrade.
3. **`.gmap` data file** — the row is fetched by the pointer that was found.

**An index miss does not turn into a full partition scan.** If the index answers “no such value”, the query answers the same way instead of reading the whole table.

Key values stay as bytes along this whole path: the index never decodes a row into objects just to compare a value.

File layout is covered in [storage (GMAP)](../understand/storage-sealed-gmap.md).

## Index updates

Indexes are updated after the mutation has been committed to the main map: data first, index second. A separate worker performs the update, so writes do not wait for trees to be rebuilt.

The practical consequence: the index trails the data rather than leading it. Point reads by primary key are unaffected.

## Long-term storage

Once a segment is sealed, secondary indexes live in fixed-page files (`.sbpt`, or `.sbm` for bitmaps). New `.sbpt` files are written as **VERSION 2** (signed INT/LONG compare on disk matches RAM). **VERSION 1** (unsigned compare) files are **rejected** on open — after a binary upgrade reseal / `dumpDomain`, do not expect silent reads of old trees: [upgrade](../configure-and-operate/operations/upgrade.md).

The in-memory index remains a hot-set accelerator and may be evicted entirely — no data is lost; the query goes to the sealed file. Under `hydrate-mode: LAZY`, the first miss after restart pays sealed-file I/O; under `FULL`, trees are warmer at the cost of a longer start.

The `index-ckpt` checkpoint stores a key watermark, not a full tree dump.

## How many indexes to build

Every index is extra work on every write and extra memory. Sensible rules:

- Index the columns you actually filter on in `WHERE` and join on in `JOIN`.
- Do not create indexes "just in case" on a write-heavy table.
- One composite index often replaces two plain ones — if the column order is chosen correctly.
- Before adding an index, look at [`EXPLAIN`](explain-and-aqe.md): the plan may already be a point lookup.

How indexes affect measured throughput — [capacity and SLO](../performance/capacity-slo.md).

## Next

- [DDL](ddl.md) — create and drop syntax.
- [EXPLAIN and AQE](explain-and-aqe.md) — how to verify that an index is used.
- [Storage (GMAP)](../understand/storage-sealed-gmap.md) — how index files are laid out.
