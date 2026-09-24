/*
 * Copyright 2024-2026 GenCloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.genfork.grid.catalog;

import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.serial.FieldMetaData;
import org.genfork.grid.serial.HashField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Catalog table layout: columns in wire ordinal order, PK, indexes, FKs, schema epoch.
 * <p>
 * SQL-first binding for encode/index (catalog columns only).
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public final class TableSchema {
	private final String tableName;
	private final List<ColumnDef> columns;
	private final Map<String, ColumnDef> byName;
	private final ColumnDef pkColumn;
	private final List<ColumnDef> pkColumns;
	private final List<IndexDef> indexes;
	private final List<FkDef> foreignKeys;
	private final List<CheckDef> checks;
	private final long schemaEpoch;
	private final FieldMetaData[] fieldMetas;
	private final Map<IndexType, List<HashField[]>> indexHashFields;
	private final FieldMetaData[] orderIndexFields;

	public TableSchema(String tableName, List<ColumnDef> columns, List<IndexDef> indexes, long schemaEpoch) {
		this(tableName, columns, indexes, List.of(), schemaEpoch);
	}

	public TableSchema(
			String tableName,
			List<ColumnDef> columns,
			List<IndexDef> indexes,
			List<FkDef> foreignKeys,
			long schemaEpoch
	) {
		this(tableName, columns, indexes, foreignKeys, List.of(), schemaEpoch, List.of());
	}

	public TableSchema(
			String tableName,
			List<ColumnDef> columns,
			List<IndexDef> indexes,
			List<FkDef> foreignKeys,
			List<CheckDef> checks,
			long schemaEpoch
	) {
		this(tableName, columns, indexes, foreignKeys, checks, schemaEpoch, List.of());
	}

	private TableSchema(
			String tableName,
			List<ColumnDef> columns,
			List<IndexDef> indexes,
			List<FkDef> foreignKeys,
			List<CheckDef> checks,
			long schemaEpoch,
			List<String> primaryKeyOrder
	) {
		if (tableName == null || tableName.isBlank()) {
			throw new IllegalArgumentException("tableName required");
		}
		Objects.requireNonNull(columns, "columns");
		if (columns.isEmpty()) {
			throw new IllegalArgumentException("columns empty");
		}
		this.tableName = tableName;
		this.columns = List.copyOf(columns);
		this.indexes = indexes == null ? List.of() : List.copyOf(indexes);
		this.foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
		this.checks = checks == null ? List.of() : List.copyOf(checks);
		this.schemaEpoch = schemaEpoch;

		final LinkedHashMap<String, ColumnDef> map = new LinkedHashMap<>(columns.size() * 2);
		final List<ColumnDef> primaryKeys = new ArrayList<>();
		for (ColumnDef col : this.columns) {
			final String key = col.name().toLowerCase(Locale.ROOT);
			if (map.put(key, col) != null) {
				throw new IllegalArgumentException("Duplicate column: " + col.name());
			}
			if (col.primaryKey()) {
				primaryKeys.add(col);
			}
		}
		if (primaryKeys.isEmpty()) {
			throw new IllegalArgumentException("PRIMARY KEY required for table " + tableName);
		}
		if (!primaryKeyOrder.isEmpty()) {
			final List<ColumnDef> ordered = new ArrayList<>(primaryKeys.size());
			for (String name : primaryKeyOrder) {
				final ColumnDef column = map.get(name.toLowerCase(Locale.ROOT));
				if (column == null || !column.primaryKey()) {
					throw new IllegalArgumentException("Unknown PRIMARY KEY column: " + name);
				}
				ordered.add(column);
			}
			if (ordered.size() != primaryKeys.size()) {
				throw new IllegalArgumentException("PRIMARY KEY order does not cover all PRIMARY KEY columns");
			}
			primaryKeys.clear();
			primaryKeys.addAll(ordered);
		}
		this.byName = Collections.unmodifiableMap(map);
		this.pkColumns = List.copyOf(primaryKeys);
		this.pkColumn = primaryKeys.getFirst();

		this.fieldMetas = new FieldMetaData[this.columns.size()];
		for (int i = 0; i < this.columns.size(); i++) {
			final ColumnDef col = this.columns.get(i);
			if (col.ordinal() != i) {
				throw new IllegalArgumentException("Column ordinal mismatch at " + i + ": " + col);
			}
			this.fieldMetas[i] = FieldMetaData.ofCatalog(col.name(), col.javaType());
		}

		final List<IndexDef> effectiveIndexes = new ArrayList<>(this.indexes);
		boolean hasPkIndex = false;
		for (IndexDef idx : effectiveIndexes) {
			if (columnsEqualIgnoreCaseOrdered(idx.columns(), primaryKeys.stream().map(ColumnDef::name).toList())
					&& idx.kind() == IndexType.STRICT) {
				hasPkIndex = true;
				break;
			}
		}
		if (!hasPkIndex) {
			effectiveIndexes.add(0, new IndexDef(
					tableName + "_pk",
					primaryKeys.stream().map(ColumnDef::name).toList(),
					IndexType.STRICT));
		}
		// FK child match uses wire EQ probes — ensure a covering LAX/STRICT/BITMAP index exists.
		for (FkDef fk : this.foreignKeys) {
			if (!hasCoveringIndex(effectiveIndexes, fk.childColumns())) {
				effectiveIndexes.add(new IndexDef(
						fk.name() + FK_CHILD_INDEX_SUFFIX,
						List.copyOf(fk.childColumns()),
						IndexType.LAX));
			}
		}

		this.indexHashFields = buildIndexHashFields(effectiveIndexes);
		this.orderIndexFields = buildOrderFields();
		validateForeignKeys();
	}

	/** Suffix for auto-wired LAX index on FOREIGN KEY child columns. */
	private static final String FK_CHILD_INDEX_SUFFIX = "_child";

	/**
	 * True when {@code indexes} already has an exact column-list match (any {@link IndexType}).
	 */
	private static boolean hasCoveringIndex(List<IndexDef> indexes, List<String> columns) {
		for (IndexDef idx : indexes) {
			if (columnsEqualIgnoreCaseOrdered(idx.columns(), columns)) {
				return true;
			}
		}
		return false;
	}

	public String tableName() {
		return tableName;
	}

	public List<ColumnDef> columns() {
		return columns;
	}

	public ColumnDef pkColumn() {
		return pkColumn;
	}

	/** Ordered PRIMARY KEY columns in DDL declaration order. */
	public List<ColumnDef> pkColumns() {
		return pkColumns;
	}

	public List<IndexDef> indexes() {
		return indexes;
	}

	public List<FkDef> foreignKeys() {
		return foreignKeys;
	}

	public List<CheckDef> checks() {
		return checks;
	}

	/** Immutable copy with an added nullable column at the end (new schema epoch). */
	public TableSchema withColumn(String name, SqlType type, boolean nullable, long newEpoch) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("column name required");
		}
		if (byName.containsKey(name.toLowerCase(Locale.ROOT))) {
			throw new IllegalStateException("Column already exists: " + name);
		}
		if (type == null) {
			throw new IllegalArgumentException("type required");
		}
		final List<ColumnDef> next = new ArrayList<>(columns.size() + 1);
		next.addAll(columns);
		next.add(new ColumnDef(name, type, nullable, columns.size(), false, false));
		return new TableSchema(tableName, next, indexes, foreignKeys, checks, newEpoch, primaryKeyNames());
	}

	/** Immutable copy without a non-PK column (new schema epoch). */
	public TableSchema withoutColumn(String name, long newEpoch) {
		final ColumnDef drop = requireColumn(name);
		if (drop.primaryKey()) {
			throw new IllegalStateException("Cannot DROP PRIMARY KEY column: " + name);
		}
		for (IndexDef idx : indexes) {
			for (String c : idx.columns()) {
				if (c.equalsIgnoreCase(name)) {
					throw new IllegalStateException("Column " + name + " is used by index " + idx.name());
				}
			}
		}
		for (FkDef fk : foreignKeys) {
			for (String c : fk.childColumns()) {
				if (c.equalsIgnoreCase(name)) {
					throw new IllegalStateException("Column " + name + " is used by FK " + fk.name());
				}
			}
		}
		final List<ColumnDef> next = new ArrayList<>(columns.size() - 1);
		int ord = 0;
		for (ColumnDef col : columns) {
			if (col.ordinal() == drop.ordinal()) {
				continue;
			}
			next.add(copyColumn(col, ord++));
		}
		return new TableSchema(tableName, next, indexes, foreignKeys, checks, newEpoch, primaryKeyNames());
	}

	/** Immutable copy with an added secondary index and new epoch. */
	public TableSchema withIndex(IndexDef indexDef, long newEpoch) {
		Objects.requireNonNull(indexDef, "indexDef");
		for (IndexDef existing : indexes) {
			if (existing.name().equalsIgnoreCase(indexDef.name())) {
				throw new IllegalStateException("Index already exists: " + indexDef.name());
			}
		}
		for (String col : indexDef.columns()) {
			requireColumn(col);
		}
		final List<IndexDef> next = new ArrayList<>(indexes.size() + 1);
		next.addAll(indexes);
		next.add(indexDef);
		return new TableSchema(tableName, columns, next, foreignKeys, checks, newEpoch, primaryKeyNames());
	}

	/** Immutable copy without named index (not the synthetic PK index). */
	public TableSchema withoutIndex(String indexName, long newEpoch) {
		if (indexName == null || indexName.isBlank()) {
			throw new IllegalArgumentException("index name required");
		}
		final List<IndexDef> next = new ArrayList<>(indexes.size());
		boolean found = false;
		for (IndexDef existing : indexes) {
			if (existing.name().equalsIgnoreCase(indexName)) {
				found = true;
				continue;
			}
			next.add(existing);
		}
		if (!found) {
			throw new IllegalStateException("Index not found: " + indexName);
		}
		return new TableSchema(tableName, columns, next, foreignKeys, checks, newEpoch, primaryKeyNames());
	}

	/** Immutable copy with an added FK (new schema epoch). */
	public TableSchema withForeignKey(FkDef fk, long newEpoch) {
		Objects.requireNonNull(fk, "fk");
		for (FkDef existing : foreignKeys) {
			if (existing.name().equalsIgnoreCase(fk.name())) {
				throw new IllegalStateException("FK already exists: " + fk.name());
			}
		}
		for (String col : fk.childColumns()) {
			requireColumn(col);
		}
		final List<FkDef> next = new ArrayList<>(foreignKeys.size() + 1);
		next.addAll(foreignKeys);
		next.add(fk);
		return new TableSchema(tableName, columns, indexes, next, checks, newEpoch, primaryKeyNames());
	}

	/** Immutable copy with an added CHECK constraint. */
	public TableSchema withCheck(CheckDef check, long newEpoch) {
		Objects.requireNonNull(check, "check");
		for (CheckDef existing : checks) {
			if (existing.name().equalsIgnoreCase(check.name())) {
				throw new IllegalStateException("CHECK already exists: " + check.name());
			}
		}
		final List<CheckDef> next = new ArrayList<>(checks);
		next.add(check);
		return new TableSchema(tableName, columns, indexes, foreignKeys, next, newEpoch, primaryKeyNames());
	}

	public IndexDef findIndex(String indexName) {
		if (indexName == null) {
			return null;
		}
		for (IndexDef existing : indexes) {
			if (existing.name().equalsIgnoreCase(indexName)) {
				return existing;
			}
		}
		return null;
	}

	public long schemaEpoch() {
		return schemaEpoch;
	}

	public ColumnDef column(String name) {
		if (name == null) {
			return null;
		}
		return byName.get(name.toLowerCase(Locale.ROOT));
	}

	public ColumnDef requireColumn(String name) {
		final ColumnDef col = column(name);
		if (col == null) {
			throw new IllegalArgumentException("Unknown column " + name + " in table " + tableName);
		}
		return col;
	}

	/** Non-transient fields in wire ordinal order (compatible with LogicalFieldCursor). */
	public FieldMetaData[] fieldMetas() {
		return fieldMetas;
	}

	public FieldMetaData pkFieldMeta() {
		return fieldMetas[pkColumn.ordinal()];
	}

	/** Ordered PRIMARY KEY field metadata. */
	public FieldMetaData[] pkFieldMetas() {
		final FieldMetaData[] fields = new FieldMetaData[pkColumns.size()];
		for (int i = 0; i < pkColumns.size(); i++) {
			fields[i] = fieldMetas[pkColumns.get(i).ordinal()];
		}
		return fields;
	}

	/** Catalog field meta by column name (case-insensitive); {@code null} if unknown. */
	public FieldMetaData fieldMeta(String name) {
		final ColumnDef col = column(name);
		if (col == null) {
			return null;
		}
		return fieldMetas[col.ordinal()];
	}

	public Map<IndexType, List<HashField[]>> indexHashFields() {
		return indexHashFields;
	}

	public FieldMetaData[] orderIndexFields() {
		return orderIndexFields;
	}

	public int columnCount() {
		return columns.size();
	}

	private List<String> primaryKeyNames() {
		return pkColumns.stream().map(ColumnDef::name).toList();
	}

	private void validateForeignKeys() {
		for (FkDef fk : foreignKeys) {
			if (!fk.childTable().equalsIgnoreCase(tableName)) {
				throw new IllegalArgumentException(
						"FK " + fk.name() + " childTable mismatch: " + fk.childTable());
			}
			for (String col : fk.childColumns()) {
				requireColumn(col);
			}
		}
	}

	private static ColumnDef copyColumn(ColumnDef col, int ordinal) {
		return new ColumnDef(
				col.name(),
				col.type(),
				col.nullable(),
				ordinal,
				col.primaryKey(),
				col.externalOrder(),
				col.identity(),
				col.identitySequence()
		);
	}

	private Map<IndexType, List<HashField[]>> buildIndexHashFields(List<IndexDef> defs) {
		final Map<IndexType, List<HashField[]>> out = new EnumMap<>(IndexType.class);
		for (IndexDef def : defs) {
			final HashField[] fields = new HashField[def.columns().size()];
			for (int i = 0; i < def.columns().size(); i++) {
				final ColumnDef col = requireColumn(def.columns().get(i));
				fields[i] = new HashField(col.nameHash(), fieldMetas[col.ordinal()]);
			}
			out.computeIfAbsent(def.kind(), _ -> new ArrayList<>()).add(fields);
		}
		return out;
	}

	private FieldMetaData[] buildOrderFields() {
		final List<FieldMetaData> order = new ArrayList<>();
		for (ColumnDef col : columns) {
			if (col.externalOrder()) {
				order.add(fieldMetas[col.ordinal()]);
			}
		}
		return order.toArray(FieldMetaData[]::new);
	}

	/**
	 * Builder for CREATE TABLE parsing / programmatic DDL.
	 */
	public static Builder builder(String tableName) {
		return new Builder(tableName);
	}

	public static final class Builder {
		private final String tableName;
		private final List<ColumnDef> columns = new ArrayList<>();
		private final List<IndexDef> indexes = new ArrayList<>();
		private final List<FkDef> foreignKeys = new ArrayList<>();
		private final List<CheckDef> checks = new ArrayList<>();
		private long schemaEpoch = 1L;
		private final List<String> pkNames = new ArrayList<>();

		private Builder(String tableName) {
			this.tableName = tableName;
		}

		public Builder schemaEpoch(long epoch) {
			this.schemaEpoch = epoch;
			return this;
		}

		public Builder column(String name, SqlType type, boolean nullable) {
			columns.add(new ColumnDef(name, type, nullable, columns.size(), false, false));
			return this;
		}

		public Builder column(String name, SqlType type) {
			return column(name, type, true);
		}

		public Builder identityColumn(String name, SqlType type, boolean primaryKey, String sequenceName) {
			if (primaryKey) {
				pkNames.add(name);
			}
			columns.add(new ColumnDef(
					name, type, false, columns.size(), primaryKey, false, true, sequenceName));
			return this;
		}

		public Builder primaryKey(String name, SqlType type) {
			pkNames.add(name);
			columns.add(new ColumnDef(name, type, false, columns.size(), true, false));
			return this;
		}

		public Builder markPrimaryKey(String name) {
			final int idx = indexOf(name);
			if (idx < 0) {
				throw new IllegalArgumentException("Unknown column for PK: " + name);
			}
			final ColumnDef old = columns.get(idx);
			columns.set(idx, new ColumnDef(
					old.name(), old.type(), false, old.ordinal(), true, old.externalOrder(),
					old.identity(), old.identitySequence()));
			if (!containsIgnoreCase(pkNames, name)) {
				pkNames.add(name);
			}
			return this;
		}

		public Builder markIdentity(String name, String sequenceName) {
			final int idx = indexOf(name);
			if (idx < 0) {
				throw new IllegalArgumentException("Unknown column for IDENTITY: " + name);
			}
			final ColumnDef old = columns.get(idx);
			columns.set(idx, new ColumnDef(
					old.name(), old.type(), old.nullable(), old.ordinal(), old.primaryKey(),
					old.externalOrder(), true, sequenceName));
			return this;
		}

		public Builder externalOrder(String name) {
			final int idx = indexOf(name);
			if (idx < 0) {
				throw new IllegalArgumentException("Unknown column for EXTERNAL ORDER: " + name);
			}
			final ColumnDef old = columns.get(idx);
			columns.set(idx, new ColumnDef(
					old.name(), old.type(), old.nullable(), old.ordinal(),
					old.primaryKey(), true, old.identity(), old.identitySequence()));
			return this;
		}

		public Builder index(IndexDef indexDef) {
			indexes.add(indexDef);
			return this;
		}

		public Builder index(String name, IndexType kind, String... cols) {
			return index(IndexDef.of(name, kind, cols));
		}

		public Builder foreignKey(FkDef fk) {
			foreignKeys.add(fk);
			return this;
		}

		public Builder check(CheckDef check) {
			checks.add(check);
			return this;
		}

		public TableSchema build() {
			if (pkNames.isEmpty()) {
				boolean found = false;
				for (ColumnDef c : columns) {
					if (c.primaryKey()) {
						found = true;
						break;
					}
				}
				if (!found) {
					throw new IllegalStateException("PRIMARY KEY required");
				}
			}
			return new TableSchema(tableName, columns, indexes, foreignKeys, checks, schemaEpoch, pkNames);
		}

		private int indexOf(String name) {
			for (int i = 0; i < columns.size(); i++) {
				if (columns.get(i).name().equalsIgnoreCase(name)) {
					return i;
				}
			}
			return -1;
		}

		private static boolean containsIgnoreCase(List<String> values, String value) {
			for (String existing : values) {
				if (existing.equalsIgnoreCase(value)) {
					return true;
				}
			}
			return false;
		}
	}

	private static boolean columnsEqualIgnoreCaseOrdered(List<String> left, List<String> right) {
		if (left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			if (!left.get(i).equalsIgnoreCase(right.get(i))) {
				return false;
			}
		}
		return true;
	}
}
