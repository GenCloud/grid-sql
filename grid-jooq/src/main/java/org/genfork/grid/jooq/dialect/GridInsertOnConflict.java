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
package org.genfork.grid.jooq.dialect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jooq.Context;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Param;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.ResultQuery;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.UniqueKey;
import org.jooq.conf.ParamType;
import org.jooq.impl.CustomQueryPart;
import org.jooq.impl.DSL;

/**
 * Grid-owned {@code INSERT … ON CONFLICT …} QueryPart for SimplifiedSql.
 * <p>
 * Renders conflict clauses directly — never MySQL {@code ON DUPLICATE KEY}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class GridInsertOnConflict extends CustomQueryPart {
	private static final String KW_INSERT_INTO = "INSERT INTO ";
	private static final String KW_VALUES = " VALUES (";
	private static final String KW_ON_CONFLICT = " ON CONFLICT (";
	private static final String KW_DO_UPDATE_SET = ") DO UPDATE SET ";
	private static final String KW_DO_NOTHING = ") DO NOTHING";
	private static final String KW_RETURNING = " RETURNING ";
	private static final String KW_EXCLUDED_DOT = "EXCLUDED.";
	private static final String SEP_COMMA = ", ";
	private static final String SEP_EQ = " = ";
	private static final char PAREN_OPEN = '(';
	private static final char PAREN_CLOSE = ')';
	private static final String ERR_TABLE = "table";
	private static final String ERR_INSERT_EMPTY = "insert columns or select required";
	private static final String ERR_CONFLICT_EMPTY = "conflict columns required";
	private static final String ERR_CONFLICT_WHERE_UNSUPPORTED =
			"ON CONFLICT DO UPDATE WHERE is not supported by SimplifiedSql";

	private final Table<?> table;
	private final LinkedHashMap<Field<?>, Object> insertValues;
	private final Select<?> selectOrNull;
	private final Field<?>[] conflictColumns;
	private final boolean doNothing;
	private final LinkedHashMap<Field<?>, Object> updateValues;
	private final Field<?>[] returningColumns;

	private GridInsertOnConflict(
			Table<?> table,
			LinkedHashMap<Field<?>, Object> insertValues,
			Select<?> selectOrNull,
			Field<?>[] conflictColumns,
			boolean doNothing,
			LinkedHashMap<Field<?>, Object> updateValues,
			Field<?>[] returningColumns
	) {
		this.table = Objects.requireNonNull(table, ERR_TABLE);
		this.insertValues = Objects.requireNonNull(insertValues);
		this.selectOrNull = selectOrNull;
		this.conflictColumns = conflictColumns;
		this.doNothing = doNothing;
		this.updateValues = updateValues;
		this.returningColumns = returningColumns;
		if (selectOrNull == null && insertValues.isEmpty()) {
			throw new IllegalArgumentException(ERR_INSERT_EMPTY);
		}
		if (conflictColumns == null || conflictColumns.length == 0) {
			throw new IllegalArgumentException(ERR_CONFLICT_EMPTY);
		}
	}

	/**
	 * Render-only QueryPart accept — writes SimplifiedSql text and visits bind Params.
	 */
	@Override
	public void accept(Context<?> ctx) {
		ctx.sql(KW_INSERT_INTO);
		ctx.sql(tableName(table));
		ctx.sql(" ");
		if (selectOrNull != null) {
			ctx.visit(selectOrNull);
		} else {
			ctx.sql(PAREN_OPEN);
			boolean firstCol = true;
			for (Field<?> field : insertValues.keySet()) {
				if (!firstCol) {
					ctx.sql(SEP_COMMA);
				}
				firstCol = false;
				ctx.sql(fieldName(field));
			}
			ctx.sql(PAREN_CLOSE);
			ctx.sql(KW_VALUES);
			boolean firstVal = true;
			for (Map.Entry<Field<?>, Object> entry : insertValues.entrySet()) {
				if (!firstVal) {
					ctx.sql(SEP_COMMA);
				}
				firstVal = false;
				acceptValue(ctx, entry.getKey(), entry.getValue());
			}
			ctx.sql(PAREN_CLOSE);
		}
		ctx.sql(KW_ON_CONFLICT);
		boolean firstConflict = true;
		for (Field<?> conflictCol : conflictColumns) {
			if (!firstConflict) {
				ctx.sql(SEP_COMMA);
			}
			firstConflict = false;
			ctx.sql(fieldName(conflictCol));
		}
		if (doNothing) {
			ctx.sql(KW_DO_NOTHING);
		} else {
			ctx.sql(KW_DO_UPDATE_SET);
			boolean firstUpd = true;
			for (Map.Entry<Field<?>, Object> entry : updateValues.entrySet()) {
				if (!firstUpd) {
					ctx.sql(SEP_COMMA);
				}
				firstUpd = false;
				ctx.sql(fieldName(entry.getKey()));
				ctx.sql(SEP_EQ);
				acceptValue(ctx, entry.getKey(), entry.getValue());
			}
		}
		if (returningColumns != null && returningColumns.length > 0) {
			ctx.sql(KW_RETURNING);
			boolean firstRet = true;
			for (Field<?> returning : returningColumns) {
				if (!firstRet) {
					ctx.sql(SEP_COMMA);
				}
				firstRet = false;
				ctx.sql(fieldName(returning));
			}
		}
	}

	/**
	 * Build an executable {@link Query} via template visit path.
	 */
	public Query toQuery(DSLContext dsl) {
		Objects.requireNonNull(dsl, "dsl");
		if (returningColumns != null && returningColumns.length > 0) {
			return dsl.resultQuery("{0}", this);
		}
		return dsl.query("{0}", this);
	}

	/**
	 * Result-bearing query when RETURNING is present.
	 */
	public ResultQuery<Record> toResultQuery(DSLContext dsl) {
		Objects.requireNonNull(dsl, "dsl");
		return dsl.resultQuery("{0}", this);
	}

	/**
	 * Indexed SQL text ({@code ?} binds) for characterization.
	 */
	public String renderIndexed(DSLContext dsl) {
		return toQuery(dsl).getSQL(ParamType.INDEXED);
	}

	/**
	 * Inlined SQL text for ANTLR accept checks.
	 */
	public String renderInlined(DSLContext dsl) {
		return toQuery(dsl).getSQL(ParamType.INLINED);
	}

	private static void acceptValue(Context<?> ctx, Field<?> field, Object value) {
		if (value instanceof ExcludedRef excludedRef) {
			ctx.sql(KW_EXCLUDED_DOT);
			ctx.sql(excludedRef.columnName());
			return;
		}
		if (value instanceof Field<?> fieldValue) {
			ctx.visit(fieldValue);
			return;
		}
		if (value instanceof Param<?> param) {
			ctx.visit(param);
			return;
		}
		@SuppressWarnings({"unchecked", "rawtypes"})
		final Param<?> param = DSL.val(value, (org.jooq.DataType) field.getDataType());
		ctx.visit(param);
	}

	private static String tableName(Table<?> table) {
		final String name = table.getName();
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException(ERR_TABLE);
		}
		return name;
	}

	private static String fieldName(Field<?> field) {
		final String name = field.getName();
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("field name required");
		}
		return name;
	}

	/**
	 * Marker for {@code EXCLUDED.column} in DO UPDATE SET maps.
	 *
	 * @author: GenCloud
	 * @date: 2026/10
	 * @since: 1.0
	 */
	public record ExcludedRef(String columnName) {
		public ExcludedRef {
			Objects.requireNonNull(columnName, "columnName");
			if (columnName.isBlank()) {
				throw new IllegalArgumentException("columnName");
			}
		}

		public static ExcludedRef of(Field<?> field) {
			return new ExcludedRef(fieldName(field));
		}
	}

	/**
	 * Fluent builder for {@link GridInsertOnConflict}.
	 *
	 * @author: GenCloud
	 * @date: 2026/10
	 * @since: 1.0
	 */
	public static final class Builder {
		private final DSLContext dsl;
		private final Table<?> table;
		private final LinkedHashMap<Field<?>, Object> insertValues = new LinkedHashMap<>();
		private Select<?> selectOrNull;
		private Field<?>[] conflictColumns;
		private boolean doNothing;
		private final LinkedHashMap<Field<?>, Object> updateValues = new LinkedHashMap<>();
		private Field<?>[] returningColumns;

		public Builder(DSLContext dsl, Table<?> table) {
			this.dsl = Objects.requireNonNull(dsl, "dsl");
			this.table = Objects.requireNonNull(table, ERR_TABLE);
		}

		public <T> Builder set(Field<T> field, T value) {
			Objects.requireNonNull(field, "field");
			insertValues.put(field, value);
			return this;
		}

		public <T> Builder set(Field<T> field, Field<T> value) {
			Objects.requireNonNull(field, "field");
			Objects.requireNonNull(value, "value");
			insertValues.put(field, value);
			return this;
		}

		public Builder set(Map<? extends Field<?>, ?> map) {
			Objects.requireNonNull(map, "map");
			for (Map.Entry<? extends Field<?>, ?> entry : map.entrySet()) {
				insertValues.put(entry.getKey(), entry.getValue());
			}
			return this;
		}

		/**
		 * Copy non-null field values from a jOOQ {@link Record} (codegen {@code newRecord} path).
		 */
		public Builder set(Record record) {
			Objects.requireNonNull(record, "record");
			for (Field<?> field : record.fields()) {
				insertValues.put(field, record.get(field));
			}
			return this;
		}

		/**
		 * {@code INSERT INTO t <select>} form (Olympiad snapshot paths).
		 */
		public Builder select(Select<?> select) {
			this.selectOrNull = Objects.requireNonNull(select, "select");
			return this;
		}

		public OnConflictStep onConflict(Field<?>... columns) {
			return new OnConflictStep(requireConflictColumns(columns));
		}

		public OnConflictStep onConflict(Collection<? extends Field<?>> columns) {
			Objects.requireNonNull(columns, "columns");
			return onConflict(columns.toArray(Field<?>[]::new));
		}

		/**
		 * Conflict target from a unique / primary key constraint (replaces jOOQ {@code onConflictOnConstraint}).
		 */
		public OnConflictStep onConflictOnConstraint(UniqueKey<?> key) {
			Objects.requireNonNull(key, "key");
			return onConflict(key.getFieldsArray());
		}

		/**
		 * Shorthand: conflict columns + {@code DO NOTHING}.
		 */
		public ReturningStep onConflictDoNothing(Field<?>... columns) {
			return onConflict(columns).doNothing();
		}

		private Field<?>[] requireConflictColumns(Field<?>[] columns) {
			if (columns == null || columns.length == 0) {
				throw new IllegalArgumentException(ERR_CONFLICT_EMPTY);
			}
			return columns;
		}

		/**
		 * After {@code onConflict(cols)}.
		 *
		 * @author: GenCloud
		 * @date: 2026/10
		 * @since: 1.0
		 */
		public final class OnConflictStep {
			private OnConflictStep(Field<?>[] columns) {
				Builder.this.conflictColumns = columns;
			}

			public DoUpdateStep doUpdate() {
				Builder.this.doNothing = false;
				return new DoUpdateStep();
			}

			public ReturningStep doNothing() {
				Builder.this.doNothing = true;
				return new ReturningStep();
			}
		}

		/**
		 * Collect DO UPDATE SET assignments.
		 *
		 * @author: GenCloud
		 * @date: 2026/10
		 * @since: 1.0
		 */
		public final class DoUpdateStep {
			public <T> DoUpdateStep set(Field<T> field, T value) {
				Objects.requireNonNull(field, "field");
				updateValues.put(field, value);
				return this;
			}

			public <T> DoUpdateStep set(Field<T> field, Field<T> value) {
				Objects.requireNonNull(field, "field");
				Objects.requireNonNull(value, "value");
				updateValues.put(field, value);
				return this;
			}

			public <T> DoUpdateStep set(Field<T> field, ExcludedRef excluded) {
				Objects.requireNonNull(field, "field");
				Objects.requireNonNull(excluded, "excluded");
				updateValues.put(field, excluded);
				return this;
			}

			public DoUpdateStep set(Map<? extends Field<?>, ?> map) {
				Objects.requireNonNull(map, "map");
				for (Map.Entry<? extends Field<?>, ?> entry : map.entrySet()) {
					updateValues.put(entry.getKey(), entry.getValue());
				}
				return this;
			}

			public DoUpdateStep set(Record record) {
				Objects.requireNonNull(record, "record");
				for (Field<?> field : record.fields()) {
					updateValues.put(field, record.get(field));
				}
				return this;
			}

			/**
			 * Unsupported — SimplifiedSql {@code ON CONFLICT DO UPDATE} has no WHERE predicate.
			 */
			public DoUpdateStep where(org.jooq.Condition ignored) {
				throw new UnsupportedOperationException(ERR_CONFLICT_WHERE_UNSUPPORTED);
			}

			/**
			 * Unsupported — SimplifiedSql {@code ON CONFLICT DO UPDATE} has no WHERE predicate.
			 */
			public DoUpdateStep and(org.jooq.Condition ignored) {
				throw new UnsupportedOperationException(ERR_CONFLICT_WHERE_UNSUPPORTED);
			}

			public ReturningStep returning(Field<?>... columns) {
				Builder.this.returningColumns = columns;
				return new ReturningStep();
			}

			public Query toQuery() {
				return build().toQuery(dsl);
			}

			public int execute() {
				return toQuery().execute();
			}

			public String getSQL(ParamType paramType) {
				return toQuery().getSQL(paramType);
			}
		}

		/**
		 * Optional RETURNING + execute.
		 *
		 * @author: GenCloud
		 * @date: 2026/10
		 * @since: 1.0
		 */
		public final class ReturningStep {
			public ReturningStep returning(Field<?>... columns) {
				Builder.this.returningColumns = columns;
				return this;
			}

			public Query toQuery() {
				return build().toQuery(dsl);
			}

			public int execute() {
				return toQuery().execute();
			}

			public String getSQL(ParamType paramType) {
				return toQuery().getSQL(paramType);
			}

			/**
			 * RETURNING path that fetches a result via {@link ResultQuery}.
			 */
			public Result<Record> fetch() {
				return build().toResultQuery(dsl).fetch();
			}
		}

		private GridInsertOnConflict build() {
			if (!doNothing && updateValues.isEmpty()) {
				throw new IllegalStateException("DO UPDATE SET requires at least one assignment");
			}
			return new GridInsertOnConflict(
					table,
					new LinkedHashMap<>(insertValues),
					selectOrNull,
					conflictColumns,
					doNothing,
					doNothing ? null : new LinkedHashMap<>(updateValues),
					returningColumns);
		}
	}

	/** Bind values in visit order (insert then update) for diagnostics. */
	List<Object> bindValuesSnapshot() {
		final List<Object> binds = new ArrayList<>(insertValues.values());
		if (!doNothing && updateValues != null) {
			for (Object value : updateValues.values()) {
				if (!(value instanceof ExcludedRef) && !(value instanceof Field<?>)) {
					binds.add(value);
				}
			}
		}
		return binds;
	}
}
