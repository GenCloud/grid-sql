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

import java.util.LinkedHashMap;
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
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.CustomQueryPart;
import org.jooq.impl.DSL;

/**
 * Grid-owned {@code INSERT … RETURNING …} (native; no {@code last_insert_rowid}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class GridReturningInsert extends CustomQueryPart {
	private static final String KW_INSERT_INTO = "INSERT INTO ";
	private static final String KW_VALUES = " VALUES (";
	private static final String KW_RETURNING = " RETURNING ";
	private static final String SEP_COMMA = ", ";
	private static final char PAREN_OPEN = '(';
	private static final char PAREN_CLOSE = ')';

	private final Table<?> table;
	private final LinkedHashMap<Field<?>, Object> insertValues;
	private final Field<?>[] returningColumns;

	public GridReturningInsert(
			Table<?> table,
			LinkedHashMap<Field<?>, Object> insertValues,
			Field<?>[] returningColumns
	) {
		this.table = Objects.requireNonNull(table, "table");
		this.insertValues = Objects.requireNonNull(insertValues, "insertValues");
		this.returningColumns = Objects.requireNonNull(returningColumns, "returningColumns");
		if (insertValues.isEmpty()) {
			throw new IllegalArgumentException("insert columns required");
		}
		if (returningColumns.length == 0) {
			throw new IllegalArgumentException("returning columns required");
		}
	}

	@Override
	public void accept(Context<?> ctx) {
		ctx.sql(KW_INSERT_INTO);
		ctx.sql(table.getName());
		ctx.sql(" ");
		ctx.sql(PAREN_OPEN);
		boolean first = true;
		for (Field<?> field : insertValues.keySet()) {
			if (!first) {
				ctx.sql(SEP_COMMA);
			}
			first = false;
			ctx.sql(field.getName());
		}
		ctx.sql(PAREN_CLOSE);
		ctx.sql(KW_VALUES);
		first = true;
		for (Map.Entry<Field<?>, Object> entry : insertValues.entrySet()) {
			if (!first) {
				ctx.sql(SEP_COMMA);
			}
			first = false;
			final Object value = entry.getValue();
			if (value instanceof Field<?> fieldValue) {
				ctx.visit(fieldValue);
			} else if (value instanceof Param<?> param) {
				ctx.visit(param);
			} else {
				@SuppressWarnings({"unchecked", "rawtypes"})
				final Param<?> param = DSL.val(value, (org.jooq.DataType) entry.getKey().getDataType());
				ctx.visit(param);
			}
		}
		ctx.sql(PAREN_CLOSE);
		ctx.sql(KW_RETURNING);
		first = true;
		for (Field<?> returning : returningColumns) {
			if (!first) {
				ctx.sql(SEP_COMMA);
			}
			first = false;
			ctx.sql(returning.getName());
		}
	}

	public Query toQuery(DSLContext dsl) {
		return toResultQuery(dsl);
	}

	public ResultQuery<Record> toResultQuery(DSLContext dsl) {
		Objects.requireNonNull(dsl, "dsl");
		return dsl.resultQuery("{0}", this);
	}

	public Result<Record> fetch(DSLContext dsl) {
		return toResultQuery(dsl).fetch();
	}

	public String getSQL(DSLContext dsl, ParamType paramType) {
		return toQuery(dsl).getSQL(paramType);
	}

	/**
	 * Fluent builder.
	 *
	 * @author: GenCloud
	 * @date: 2026/10
	 * @since: 1.0
	 */
	public static final class Builder {
		private final DSLContext dsl;
		private final Table<?> table;
		private final LinkedHashMap<Field<?>, Object> insertValues = new LinkedHashMap<>();

		public Builder(DSLContext dsl, Table<?> table) {
			this.dsl = Objects.requireNonNull(dsl, "dsl");
			this.table = Objects.requireNonNull(table, "table");
		}

		public <T> Builder set(Field<T> field, T value) {
			insertValues.put(Objects.requireNonNull(field, "field"), value);
			return this;
		}

		public ReturningStep returning(Field<?>... columns) {
			if (columns == null || columns.length == 0) {
				throw new IllegalArgumentException("returning columns required");
			}
			return new ReturningStep(columns);
		}

		/**
		 * @author: GenCloud
		 * @date: 2026/10
		 * @since: 1.0
		 */
		public final class ReturningStep {
			private final Field<?>[] columns;

			ReturningStep(Field<?>[] columns) {
				this.columns = columns;
			}

			public Query toQuery() {
				return new GridReturningInsert(table, new LinkedHashMap<>(insertValues), columns)
						.toQuery(dsl);
			}

			public Result<Record> fetch() {
				return new GridReturningInsert(table, new LinkedHashMap<>(insertValues), columns)
						.fetch(dsl);
			}

			public String getSQL(ParamType paramType) {
				return toQuery().getSQL(paramType);
			}
		}
	}
}
