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
package org.genfork.grid.sql.exec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.sql.SqlBuiltinEvalUtil;
import org.genfork.grid.sql.SqlBuiltinExpr.CaseExpr;
import org.genfork.grid.sql.SqlBuiltinExpr.ClockExpr;
import org.genfork.grid.sql.SqlBuiltinExpr.CoalesceExpr;
import org.genfork.grid.sql.SqlBuiltinExpr.ColumnRef;
import org.genfork.grid.sql.SqlBuiltinExpr.ExcludedRef;
import org.genfork.grid.sql.SqlBuiltinExpr.NumericColPlus;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;

/**
 * Resolve UPDATE SET markers ({@link ColumnRef}, {@link CaseExpr}, …) against existing row bytes.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class SqlDmlSetResolveUtil {
	private SqlDmlSetResolveUtil() {
	}

	/** {@code true} when any SET value still needs row/session resolution. */
	public static boolean needsResolve(Map<String, Object> sets) {
		if (sets == null || sets.isEmpty()) {
			return false;
		}
		for (Object v : sets.values()) {
			if (isMarker(v)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Materialize markers using the existing row (or {@code null} values when base missing).
	 */
	public static Map<String, Object> resolveSets(
			SqlSession session,
			TableStore store,
			byte[] existingOrNull,
			Map<String, Object> sets
	) {
		Objects.requireNonNull(sets, "sets");
		if (!needsResolve(sets)) {
			return sets;
		}
		final TableSchema schema = store.schema();
		final Object[] existingValues = existingOrNull != null && LogicalFieldCursor.canOpen(schema, existingOrNull)
				? RowEncoder.decode(schema, existingOrNull)
				: null;
		final Function<String, Object> columnValue = name -> {
			if (existingValues == null) {
				return null;
			}
			return existingValues[schema.requireColumn(name).ordinal()];
		};
		final Map<String, Object> out = new LinkedHashMap<>(sets.size() * 2);
		for (Map.Entry<String, Object> e : sets.entrySet()) {
			final ColumnDef col = schema.requireColumn(e.getKey());
			out.put(e.getKey(), SqlBuiltinEvalUtil.resolve(
					e.getValue(),
					columnValue,
					null,
					session.timezone(),
					col.type()));
		}
		return out;
	}

	private static boolean isMarker(Object v) {
		return v instanceof ClockExpr
				|| v instanceof CoalesceExpr
				|| v instanceof ColumnRef
				|| v instanceof ExcludedRef
				|| v instanceof NumericColPlus
				|| v instanceof CaseExpr;
	}
}
