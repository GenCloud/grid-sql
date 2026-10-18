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

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.serial.PrimaryKeyCodec;
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
	/**
	 * Prefix so UNIQUE-target arbiter lock keys never collide with primary-key wire bytes.
	 */
	private static final byte CONFLICT_ARBITER_MAGIC_0 = (byte) 0xC0;
	private static final byte CONFLICT_ARBITER_MAGIC_1 = (byte) 0xFF;
	private static final int CONFLICT_ARBITER_MAGIC_LEN = 2;
	private static final int LEN_PREFIX_BYTES = 4;

	private SqlMergeMatchOps() {
	}

	/**
	 * {@code true} when conflict target is omitted or matches the full PRIMARY KEY column list.
	 */
	static boolean targetsPrimaryKey(TableSchema schema, List<String> targets) {
		if (targets == null || targets.isEmpty()) {
			return true;
		}
		final List<ColumnDef> pkColumns = schema.pkColumns();
		final List<String> pkNames = new ArrayList<>(pkColumns.size());
		for (int i = 0; i < pkColumns.size(); i++) {
			pkNames.add(pkColumns.get(i).name());
		}
		return columnsEqualIgnoreCase(pkNames, targets);
	}

	/**
	 * Lock key that serializes concurrent {@code ON CONFLICT} on the same UNIQUE target
	 * (PK target reuses PK bytes; secondary UNIQUE uses a tagged wire blob).
	 */
	static byte[] conflictArbiterLockKey(TableStore store, Object[] row, OnConflict conflict) {
		final TableSchema schema = store.schema();
		final List<String> targets = conflict.targetColumns() == null
				? List.of()
				: conflict.targetColumns();
		if (targetsPrimaryKey(schema, targets)) {
			return PrimaryKeyCodec.encodeRow(schema, row);
		}
		validateUniqueTarget(schema, targets);
		int total = CONFLICT_ARBITER_MAGIC_LEN;
		final byte[][] parts = new byte[targets.size()][];
		for (int i = 0; i < targets.size(); i++) {
			parts[i] = SqlWireUtil.toGenericArray(row[schema.requireColumn(targets.get(i)).ordinal()]);
			total += LEN_PREFIX_BYTES + (parts[i] == null ? 0 : parts[i].length);
		}
		final byte[] out = new byte[total];
		out[0] = CONFLICT_ARBITER_MAGIC_0;
		out[1] = CONFLICT_ARBITER_MAGIC_1;
		int pos = CONFLICT_ARBITER_MAGIC_LEN;
		for (byte[] part : parts) {
			final int len = part == null ? 0 : part.length;
			out[pos++] = (byte) (len >>> 24);
			out[pos++] = (byte) (len >>> 16);
			out[pos++] = (byte) (len >>> 8);
			out[pos++] = (byte) len;
			if (len > 0) {
				System.arraycopy(part, 0, out, pos, len);
				pos += len;
			}
		}
		return out;
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
	 * Conflict target → row key bytes (PK or UNIQUE/STRICT index wire match).
	 */
	static byte[] resolveConflictKey(TableStore store, Object[] row, OnConflict conflict) {
		final TableSchema schema = store.schema();
		final List<String> targets = conflict.targetColumns() == null
				? List.of()
				: conflict.targetColumns();
		if (targetsPrimaryKey(schema, targets)) {
			return PrimaryKeyCodec.encodeRow(schema, row);
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
		if (targetsPrimaryKey(schema, columns)) {
			return PrimaryKeyCodec.encodeValues(schema, values.toArray());
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
		if (targetsPrimaryKey(schema, columns)) {
			return;
		}
		for (IndexDef idx : schema.indexes()) {
			if (idx.kind() != IndexType.STRICT) {
				continue;
			}
			if (columnsEqualIgnoreCase(idx.columns(), columns)) {
				return;
			}
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
