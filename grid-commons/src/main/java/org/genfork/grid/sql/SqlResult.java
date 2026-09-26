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
package org.genfork.grid.sql;

import org.genfork.grid.catalog.SqlType;

import java.util.List;
import java.util.Objects;

/**
 * SQL execution result: DDL ack, rows affected, or result set.
 *
 * Lives in {@code grid-commons} (shared client/server; JDK-only).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlResult {
	public enum Kind {
		OK_DDL,
		ROWS_AFFECTED,
		RESULT_SET
	}

	private final Kind kind;
	private final SqlStatementTag tag;
	private final long rowsAffected;
	private final List<ColumnMeta> columns;
	private List<Object[]> rows;
	private final SqlRowWindowSource windowSource;

	private SqlResult(
			Kind kind,
			SqlStatementTag tag,
			long rowsAffected,
			List<ColumnMeta> columns,
			List<Object[]> rows,
			SqlRowWindowSource windowSource
	) {
		this.kind = kind;
		this.tag = Objects.requireNonNull(tag, "tag");
		this.rowsAffected = rowsAffected;
		this.columns = columns;
		this.rows = rows;
		this.windowSource = windowSource;
	}

	public static SqlResult ddl(SqlStatementTag tag) {
		return new SqlResult(Kind.OK_DDL, tag, 0L, List.of(), List.of(), null);
	}

	public static SqlResult affected(SqlStatementTag tag, long rowsAffected) {
		return new SqlResult(Kind.ROWS_AFFECTED, tag, rowsAffected, List.of(), List.of(), null);
	}

	public static SqlResult resultSet(List<ColumnMeta> columns, List<Object[]> rows) {
		return resultSet(SqlStatementTag.SELECT, columns, rows);
	}

	public static SqlResult resultSet(
			SqlStatementTag tag,
			List<ColumnMeta> columns,
			List<Object[]> rows
	) {
		return new SqlResult(Kind.RESULT_SET, tag, rows.size(), columns, rows, null);
	}

	
	/**
	 * RESULT_SET backed by a pull cursor (Portal FETCH without full List materialize).
	 * {@link #rows()} drains the cursor once for in-process SPI callers.
	 */
	public static SqlResult resultSetPull(
			SqlStatementTag tag,
			List<ColumnMeta> columns,
			SqlRowWindowSource windowSource
	) {
		Objects.requireNonNull(windowSource, "windowSource");
		return new SqlResult(Kind.RESULT_SET, tag, 0L, columns, null, windowSource);
	}

	public static SqlResult resultSetPull(List<ColumnMeta> columns, SqlRowWindowSource windowSource) {
		return resultSetPull(SqlStatementTag.SELECT, columns, windowSource);
	}

	public Kind kind() {
		return kind;
	}

	public SqlStatementTag statementTag() {
		return tag;
	}

	/** Wire label for EXEC_DONE (stable string). */
	public String tag() {
		return tag.wire();
	}

	public long rowsAffected() {
		return rowsAffected;
	}

	public List<ColumnMeta> columns() {
		return columns;
	}

	public List<Object[]> rows() {
		if (rows != null) {
			return rows;
		}
		if (windowSource != null) {
			// Cache drain so repeated rows() / size()+getFirst() do not see an empty closed cursor.
			rows = windowSource.drainAll();
			return rows;
		}
		return List.of();
	}

	/**
	 * Pull cursor for wire Portal FETCH; {@code null} when rows are fully eager.
	 */
	public SqlRowWindowSource windowSource() {
		return windowSource;
	}

	/** Column metadata for result sets / wire ROW_DESC. */
	public record ColumnMeta(
			String name,
			SqlType type,
			Boolean nullableOrNull,
			Integer precisionOrNull,
			String tableOrNull,
			String schemaOrNull
	) {
		public static ColumnMeta of(String name, SqlType type) {
			return new ColumnMeta(name, type, null, null, null, null);
		}

		public static ColumnMeta ofCatalog(
				String name,
				SqlType type,
				boolean nullable,
				String table,
				String schema
		) {
			return new ColumnMeta(
					name,
					type,
					Boolean.valueOf(nullable),
					Integer.valueOf(ColumnMetas.precision(type)),
					table,
					schema);
		}

		public boolean nullableKnown() {
			return nullableOrNull != null;
		}

		public boolean nullable() {
			return nullableOrNull != null && nullableOrNull;
		}

		public int precision() {
			return precisionOrNull != null ? precisionOrNull : ColumnMetas.precision(type);
		}

		public String tableName() {
			return tableOrNull == null ? "" : tableOrNull;
		}

		public String schemaName() {
			return schemaOrNull == null ? "" : schemaOrNull;
		}
	}
}