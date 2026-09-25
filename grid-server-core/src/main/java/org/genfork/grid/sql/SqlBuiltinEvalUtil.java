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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.sql.SqlBuiltinExpr.ClockExpr;
import org.genfork.grid.sql.SqlBuiltinExpr.ClockKind;
import org.genfork.grid.sql.SqlBuiltinExpr.CoalesceExpr;
import org.genfork.grid.sql.SqlBuiltinExpr.ColumnRef;
import org.genfork.grid.sql.SqlBuiltinExpr.ExcludedRef;
import org.genfork.grid.sql.ast.SelectAst.ColumnFuncArg;
import org.genfork.grid.sql.ast.SelectAst.FuncArg;
import org.genfork.grid.sql.ast.SelectAst.LiteralFuncArg;

/**
 * Shared evaluation for dialect builtins (COALESCE / clock) and DML marker resolution.
 * <p>
 * Named builtins dispatch via {@link EnumMap} of {@link SqlBuiltinNames}. DML SET / DEFAULT /
 * ON CONFLICT markers ({@link CoalesceExpr}, {@link ClockExpr}, …) go through {@link #resolve}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlBuiltinEvalUtil {
	@FunctionalInterface
	private interface BuiltinEvaluator {
		Object eval(List<FuncArg> args, Function<String, Object> columnValue, ZoneId zone);
	}

	private static final Map<SqlBuiltinNames, BuiltinEvaluator> EVALUATORS = buildEvaluators();

	private SqlBuiltinEvalUtil() {
	}

	private static Map<SqlBuiltinNames, BuiltinEvaluator> buildEvaluators() {
		final EnumMap<SqlBuiltinNames, BuiltinEvaluator> m = new EnumMap<>(SqlBuiltinNames.class);
		m.put(SqlBuiltinNames.COALESCE, SqlBuiltinEvalUtil::evalCoalesceArgs);
		m.put(SqlBuiltinNames.NOW, (args, columnValue, zone) ->
				materializeClock(ClockKind.NOW, zone, null));
		m.put(SqlBuiltinNames.CURRENT_TIMESTAMP, (args, columnValue, zone) ->
				materializeClock(ClockKind.CURRENT_TIMESTAMP, zone, null));
		m.put(SqlBuiltinNames.CURRENT_DATE, (args, columnValue, zone) ->
				materializeClock(ClockKind.CURRENT_DATE, zone, SqlType.DATE));
		for (SqlBuiltinNames name : SqlBuiltinNames.values()) {
			if (!m.containsKey(name)) {
				throw new IllegalStateException("builtin evaluator missing: " + name);
			}
		}
		return Map.copyOf(m);
	}

	public static boolean isBuiltin(String name) {
		return SqlBuiltinNames.isBuiltin(name);
	}

	public static SqlType builtinReturnType(String name) {
		final SqlBuiltinNames b = SqlBuiltinNames.find(name);
		return b == null ? null : b.returnType();
	}

	public static Object evalBuiltin(
			String functionName,
			List<FuncArg> args,
			Function<String, Object> columnValue,
			ZoneId zone
	) {
		final SqlBuiltinNames builtin = SqlBuiltinNames.require(functionName);
		return EVALUATORS.get(builtin).eval(args, columnValue, zone);
	}

	/**
	 * Resolve a DML value that may be a literal or builtin marker
	 * ({@link ClockExpr}, {@link CoalesceExpr}, {@link ColumnRef}, {@link ExcludedRef}).
	 *
	 * @param excludedValue maps EXCLUDED.col → proposed INSERT value; {@code null} when not in
	 *                      ON CONFLICT DO UPDATE
	 * @param targetTypeOrNull optional column type (e.g. DATE vs TIMESTAMPTZ for clock)
	 */
	public static Object resolve(
			Object expr,
			Function<String, Object> columnValue,
			Function<String, Object> excludedValue,
			ZoneId zone,
			SqlType targetTypeOrNull
	) {
		if (expr == null) {
			return null;
		}
		if (expr instanceof ClockExpr clock) {
			return materializeClock(clock.kind(), zone, targetTypeOrNull);
		}
		if (expr instanceof ColumnRef ref) {
			return columnValue.apply(ref.column());
		}
		if (expr instanceof ExcludedRef ref) {
			if (excludedValue == null) {
				throw new IllegalArgumentException("EXCLUDED only valid in ON CONFLICT DO UPDATE");
			}
			return excludedValue.apply(ref.column());
		}
		if (expr instanceof CoalesceExpr coal) {
			for (Object arg : coal.args()) {
				final Object v = resolve(arg, columnValue, excludedValue, zone, targetTypeOrNull);
				if (v != null) {
					return v;
				}
			}
			return null;
		}
		return expr;
	}

	public static Object materializeClock(ClockKind kind, ZoneId zone, SqlType asTypeOrNull) {
		final ZoneId z = zone != null ? zone : ZoneId.of("UTC");
		if (kind == ClockKind.CURRENT_DATE || asTypeOrNull == SqlType.DATE) {
			return LocalDate.now(z);
		}
		return Instant.now();
	}

	public static Object coalesceResolved(Object... values) {
		if (values == null) {
			return null;
		}
		for (Object v : values) {
			if (v != null) {
				return v;
			}
		}
		return null;
	}

	private static Object evalCoalesceArgs(
			List<FuncArg> args,
			Function<String, Object> columnValue,
			ZoneId zone
	) {
		if (args == null || args.size() < 2) {
			throw new IllegalArgumentException("COALESCE requires at least two arguments");
		}
		final Object[] resolved = new Object[args.size()];
		for (int i = 0; i < args.size(); i++) {
			resolved[i] = resolveFuncArg(args.get(i), columnValue, zone);
		}
		return coalesceResolved(resolved);
	}

	private static Object resolveFuncArg(
			FuncArg arg,
			Function<String, Object> columnValue,
			ZoneId zone
	) {
		if (arg == null) {
			return null;
		}
		if (arg instanceof ColumnFuncArg c) {
			return columnValue.apply(c.column());
		}
		if (arg instanceof LiteralFuncArg lit) {
			return resolve(lit.value(), columnValue, null, zone, null);
		}
		throw new IllegalArgumentException("unsupported func arg: " + arg.getClass().getName());
	}
}
