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

import java.util.List;

import org.genfork.grid.catalog.TableSchema;

/**
 * Gates scalar PRIMARY KEY point-lookup fast paths.
 * <p>
 * Composite PRIMARY KEY tables must not treat EQ on the first PK column as
 * {@code getByPk(scalar)} — that path requires a full key tuple. JOIN uses the
 * same rule via {@link #isJoinPkPointProbe}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlPkLookupUtil {
	/** JOIN map point-lookup requires exactly one ON equality. */
	private static final int JOIN_PK_PROBE_EQ_ARITY = 1;

	private SqlPkLookupUtil() {
	}

	/**
	 * True when {@code pkColumnHint} names the sole PRIMARY KEY column of {@code schema}.
	 */
	public static boolean isScalarPkPointLookup(TableSchema schema, String pkColumnHint) {
		if (schema == null || pkColumnHint == null || pkColumnHint.isBlank()) {
			return false;
		}
		if (schema.pkColumns().size() != 1) {
			return false;
		}
		return schema.pkColumn().name().equalsIgnoreCase(pkColumnHint);
	}

	/**
	 * True when JOIN ON columns allow {@code getCommittedBytes} / scalar PK probe.
	 * <p>
	 * Only a single-column ON against a scalar PRIMARY KEY qualifies. Partial EQ on a
	 * composite PRIMARY KEY must use index prefix or hash JOIN instead.
	 */
	public static boolean isJoinPkPointProbe(TableSchema schema, List<String> onColNames) {
		if (onColNames == null || onColNames.size() != JOIN_PK_PROBE_EQ_ARITY) {
			return false;
		}
		return isScalarPkPointLookup(schema, onColNames.getFirst());
	}
}
