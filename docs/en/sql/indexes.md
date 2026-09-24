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

## Primary key

There is no need to index the primary key separately. It determines the shard, and equality on the full primary key becomes a point read — no scan and no secondary index lookup.

## Query path

An index lookup goes through three levels, top down:

1. **In-memory index** — the working-set accelerator.
2. **Sealed `.sbpt` tree** — an immutable fixed-page B+ tree on disk.
3. **`.gmap` data file** — the row is fetched by the pointer that was found.

The key property: **an index miss does not turn into a full partition scan**. If the index answers "no such value", the query answers the same way instead of starting to read the whole table in hope of finding something.

Key values stay as bytes along this whole path: the index never decodes a row into objects just to compare a value.

File layout is covered in [storage (GMAP)](../understand/storage-sealed-gmap.md).

## Index updates

Indexes are updated after the mutation has been committed to the main map: data first, index second. A separate worker performs the update, so writes do not wait for trees to be rebuilt.

The practical consequence: the index trails the data rather than leading it. Point reads by primary key are unaffected.

## Long-term storage

Once a segment is sealed, secondary indexes live in fixed-page files (`.sbpt`, or `.sbm` for bitmaps). The in-memory index remains a working-set accelerator and may be evicted entirely — no data is lost by that, the query simply goes to the sealed file.

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
