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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.ast.DmlAst.OnConflict;
import org.genfork.grid.store.TableStore;

/**
 * MERGE / ON CONFLICT match-key and named-map helpers for {@link SqlDmlExecutor}.
 * <p>
 * Conflict / MERGE targets resolve to row {@code byte[]} keys via PK or UNIQUE/STRICT
 * index wire EQ ({@link SqlWireUtil#toGenericArray}).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlMergeMatchOps {
	private SqlMergeMatchOps() {
	}

	static boolean namedHasNonNull(Map<String, Object> named, String col) {
		for (Map.Entry<String, Object> e : named.entrySet()) {
			if (e.getKey().equalsIgnoreCase(col)) {
				return e.getValue() != null;
			}
		}
		return false;
	}

	static boolean sourceNamedContains(Map<String, Object> named, String col) {
		for (String key : named.keySet()) {
			if (key.equalsIgnoreCase(col)) {
				return true;
			}
		}
		return false;
	}

	static Object lookupNamed(Map<String, Object> named, String col) {
		for (Map.Entry<String, Object> e : named.entrySet()) {
			if (e.getKey().equalsIgnoreCase(col)) {
				return e.getValue();
			}
		}
		return null;
	}

	/**
	 * Conflict target â' row key bytes (PK or UNIQUE/STRICT index wire match).
	 */
	static byte[] resolveConflictKey(TableStore store, Object[] row, OnConflict conflict) {
		final TableSchema schema = store.schema();
		final List<String> targets = conflict.targetColumns() == null
				? List.of()
				: conflict.targetColumns();
		if (targets.isEmpty()
				|| (targets.size() == 1
				&& targets.getFirst().equalsIgnoreCase(schema.pkColumn().name()))) {
			final Object pk = row[schema.pkColumn().ordinal()];
			return store.keyBytesForPk(pk);
		}
		validateUniqueTarget(schema, targets);
		final List<Object> vals = new ArrayList<>(targets.size());
		for (String col : targets) {
			vals.add(row[schema.requireColumn(col).ordinal()]);
		}
		return resolveMatchKey(store, targets, vals);
	}

	static byte[] resolveMatchKey(TableStore store, String singleCol, List<Object> values) {
		return resolveMatchKey(store, List.of(singleCol), values);
	}

	static byte[] resolveMatchKey(TableStore store, List<String> columns, List<Object> values) {
		final TableSchema schema = store.schema();
		if (columns.size() == 1
				&& columns.getFirst().equalsIgnoreCase(schema.pkColumn().name())) {
			return store.keyBytesForPk(values.getFirst());
		}
		validateUniqueTarget(schema, columns);
		final byte[][] want = new byte[columns.size()][];
		for (int i = 0; i < columns.size(); i++) {
			want[i] = SqlWireUtil.toGenericArray(values.get(i));
		}
		if (!store.hasEqIndex(columns)) {
			throw new IllegalArgumentException(GridCompositeIndex.MSG_EQ_REQUIRES_INDEX + ": " + columns);
		}
		final List<byte[]> candidates = store.lookupEqKeys(columns, want);
		if (candidates.isEmpty()) {
			return null;
		}
		return candidates.getFirst();
	}

	static void validateUniqueTarget(TableSchema schema, List<String> columns) {
		for (IndexDef idx : schema.indexes()) {
			if (idx.kind() != IndexType.STRICT) {
				continue;
			}
			if (columnsEqualIgnoreCase(idx.columns(), columns)) {
				return;
			}
		}
		if (columns.size() == 1
				&& columns.getFirst().equalsIgnoreCase(schema.pkColumn().name())) {
			return;
		}
		throw new IllegalArgumentException(
				"ON CONFLICT / MERGE target must be PRIMARY KEY or UNIQUE index columns");
	}

	static boolean columnsEqualIgnoreCase(List<String> left, List<String> right) {
		if (left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			if (!left.get(i).equalsIgnoreCase(right.get(i))) {
				return false;
			}
		}
		return true;
	}
}
