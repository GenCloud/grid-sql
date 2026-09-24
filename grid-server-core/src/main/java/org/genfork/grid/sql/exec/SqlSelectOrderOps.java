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

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;

/**
 * ANTLR helpers for ORDER BY / LIMIT decisions on already-typed {@link SelectSql}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlSelectOrderOps {
	private SqlSelectOrderOps() {
	}

	/**
	 * True when the SELECT text has an ORDER BY list (ANTLR parse of {@link SelectSql#sql()}).
	 */
	public static boolean hasOrderBy(SelectSql s) {
		if (s == null || s.sql() == null || s.sql().isBlank()) {
			return false;
		}
		try {
			final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(s.sql()));
			final SimplifiedSqlParser parser = new SimplifiedSqlParser(new CommonTokenStream(lexer));
			final SimplifiedSqlParser.QueryContext q = parser.query();
			if (q == null || q.selectQuery() == null) {
				return false;
			}
			final SimplifiedSqlParser.SelectQueryContext sq = q.selectQuery();
			return sq.ORDER() != null && sq.orderList() != null
					&& !sq.orderList().orderItem().isEmpty();
		} catch (RuntimeException ex) {
			return true;
		}
	}

	/**
	 * Early LIMIT for join kernels when LIMIT present, no OFFSET, no ORDER BY.
	 */
	public static int earlyLimitOrZero(SelectSql s) {
		if (s == null || s.limitOrNull() == null || s.limitOrNull() <= 0 || s.offset() > 0) {
			return 0;
		}
		if (hasOrderBy(s)) {
			return 0;
		}
		return s.limitOrNull();
	}
}