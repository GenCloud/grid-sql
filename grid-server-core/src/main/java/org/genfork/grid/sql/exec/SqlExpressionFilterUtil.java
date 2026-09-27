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

import java.util.Objects;

import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.QueryParser;

/**
 * ANTLR expression → {@link FilterCondition} helpers (no synthetic SELECT wrap).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class SqlExpressionFilterUtil {
	private SqlExpressionFilterUtil() {
	}

	/**
	 * Parse a WHERE / CHECK / WHEN boolean fragment via ANTLR {@code standaloneExpression}.
	 */
	public static FilterCondition parseExpression(String expressionSql) {
		Objects.requireNonNull(expressionSql, "expressionSql");
		return QueryParser.parseExpression(expressionSql);
	}

	/**
	 * Evaluate a CHECK / WHEN expression against a row blob (wire bytes).
	 */
	public static boolean matchesExpression(String expressionSql, byte[] valueBytes, TableSchema schema) {
		Objects.requireNonNull(expressionSql, "expressionSql");
		Objects.requireNonNull(valueBytes, "valueBytes");
		Objects.requireNonNull(schema, "schema");
		final FilterCondition condition = parseExpression(expressionSql);
		return condition.matches(valueBytes, schema);
	}
}