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

import java.util.List;
import java.util.Objects;

import org.genfork.grid.query.filters.FilterCondition;

/**
 * Parse-time markers for dialect builtins carried in SET / INSERT / ON CONFLICT maps.
 * <p>
 * Materialized to SPI {@link Object} only at encode / projection boundaries
 * ({@link SqlBuiltinEvalUtil}).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlBuiltinExpr {
	private SqlBuiltinExpr() {
	}

	/** {@code EXCLUDED.col} in {@code ON CONFLICT DO UPDATE}. */
	public record ExcludedRef(String column) {
		public ExcludedRef {
			Objects.requireNonNull(column, "column");
			if (column.isBlank()) {
				throw new IllegalArgumentException("EXCLUDED column required");
			}
		}
	}

	/** Column reference inside {@code COALESCE} / {@code SET col = other_col}. */
	public record ColumnRef(String column) {
		public ColumnRef {
			Objects.requireNonNull(column, "column");
			if (column.isBlank()) {
				throw new IllegalArgumentException("column required");
			}
		}
	}

	/** {@code col + literal} in CASE THEN/ELSE (numeric RMW fragment). */
	public record NumericColPlus(String column, Object addend) {
		public NumericColPlus {
			Objects.requireNonNull(column, "column");
			if (column.isBlank()) {
				throw new IllegalArgumentException("column required");
			}
			Objects.requireNonNull(addend, "addend");
		}
	}

	/**
	 * Row-aware {@code CASE WHEN … THEN … [ELSE …] END} for UPDATE SET.
	 * <p>
	 * {@code thenValue}/{@code elseValue} are literals or markers
	 * ({@link ColumnRef}, {@link NumericColPlus}, nested builtins).
	 */
	public record CaseBranch(FilterCondition when, Object thenValue) {
		public CaseBranch {
			Objects.requireNonNull(when, "when");
		}
	}

	/** Deferred CASE evaluated against the existing row at encode time. */
	public record CaseExpr(List<CaseBranch> branches, Object elseValueOrNull) {
		public CaseExpr {
			Objects.requireNonNull(branches, "branches");
			if (branches.isEmpty()) {
				throw new IllegalArgumentException("CASE requires at least one WHEN");
			}
			branches = List.copyOf(branches);
		}
	}

	/** {@code COALESCE(a, b, …)} — args are literals, {@link ColumnRef}, nested markers. */
	public record CoalesceExpr(List<Object> args) {
		public CoalesceExpr {
			Objects.requireNonNull(args, "args");
			if (args.size() < 2) {
				throw new IllegalArgumentException("COALESCE requires at least two arguments");
			}
			args = List.copyOf(args);
		}
	}

	/** Clock builtins for DEFAULT / value / SELECT. */
	public enum ClockKind {
		NOW,
		CURRENT_TIMESTAMP,
		CURRENT_DATE
	}

	/** {@code NOW()} / {@code CURRENT_TIMESTAMP} / {@code CURRENT_DATE}. */
	public record ClockExpr(ClockKind kind) {
		public ClockExpr {
			Objects.requireNonNull(kind, "kind");
		}
	}
}