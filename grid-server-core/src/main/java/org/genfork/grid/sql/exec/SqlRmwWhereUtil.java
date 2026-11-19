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

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.plan.UpdatePlan;
import org.genfork.grid.sql.SqlNullSafeCollections;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;

/**
 * RMW UPDATE WHERE: resolve scalar PK among AND equality conjuncts; residual EQ filter on base blob.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SqlRmwWhereUtil {
	private static final String RMW_REQUIRES_PK_EQUALITY = "RMW UPDATE requires PK equality WHERE in v1.1";
	private static final String RMW_COMPOSITE_PK_UNSUPPORTED = "RMW UPDATE requires scalar PK in v1.1";

	private SqlRmwWhereUtil() {
	}

	/**
	 * Fill {@link UpdatePlan} / PK fields from schema + {@link UpdateSql#whereEqLiterals()}.
	 * No-op when statement is not RMW.
	 */
	public static UpdateSql resolveRmw(UpdateSql statement, TableSchema schema) {
		Objects.requireNonNull(statement, "statement");
		Objects.requireNonNull(schema, "schema");
		if (statement.rmw() == null) {
			return statement;
		}
		if (schema.pkColumns().size() != 1) {
			throw new IllegalArgumentException(RMW_COMPOSITE_PK_UNSUPPORTED);
		}
		final ColumnDef pk = schema.pkColumn();
		final String pkName = pk.name();
		final Map<String, Object> eqs = statement.whereEqLiterals();
		// Require PK column among peeled equalities — never trust provisional non-PK pkValueOrNull.
		final Object pkValue = lookupEquality(eqs, pkName, null);
		if (pkValue == null) {
			throw new IllegalArgumentException(RMW_REQUIRES_PK_EQUALITY);
		}
		final UpdatePlan resolved = new UpdatePlan(
				statement.rmw().table(),
				statement.rmw().assigns(),
				pkName,
				pkValue,
				statement.rmw().templateKey());
		return new UpdateSql(
				statement.table(),
				resolved,
				statement.setLiterals(),
				statement.whereSql(),
				pkName,
				pkValue,
				statement.sourceTableOrNull(),
				statement.targetJoinColumnOrNull(),
				statement.sourceJoinColumnOrNull(),
				statement.returning(),
				SqlNullSafeCollections.freezeMap(eqs));
	}

	/**
	 * {@code true} when every non-PK equality conjunct matches {@code base} on the wire.
	 * Empty residual (PK-only WHERE) always matches.
	 */
	public static boolean residualMatches(TableSchema schema, byte[] base, UpdateSql statement) {
		Objects.requireNonNull(schema, "schema");
		Objects.requireNonNull(base, "base");
		Objects.requireNonNull(statement, "statement");
		final String pkName = statement.pkColumnOrNull() != null
				? statement.pkColumnOrNull()
				: schema.pkColumn().name();
		final Map<String, Object> eqs = statement.whereEqLiterals();
		if (eqs == null || eqs.isEmpty()) {
			return true;
		}
		for (Map.Entry<String, Object> entry : eqs.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(pkName)) {
				continue;
			}
			final LogicalOperatorCondition condition = new LogicalOperatorCondition(
					entry.getKey(),
					LogicalOperatorCondition.Operator.EQ,
					entry.getValue());
			condition.validate(schema);
			if (!condition.matches(base, schema)) {
				return false;
			}
		}
		return true;
	}

	private static Object lookupEquality(Map<String, Object> eqs, String column, Object pkValueHint) {
		if (eqs != null && !eqs.isEmpty() && column != null) {
			final Object fromMap = eqs.get(column);
			if (fromMap != null) {
				return fromMap;
			}
			final String lower = column.toLowerCase(Locale.ROOT);
			for (Map.Entry<String, Object> entry : eqs.entrySet()) {
				if (entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
					return entry.getValue();
				}
			}
		}
		return pkValueHint;
	}
}
