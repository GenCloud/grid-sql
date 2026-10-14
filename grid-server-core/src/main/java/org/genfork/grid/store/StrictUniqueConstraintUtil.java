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
package org.genfork.grid.store;

import java.util.Arrays;
import java.util.List;

import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.WireSpan;

/**
 * STRICT secondary UNIQUE checks via wire EQ probe (RAM + sealed fallback).
 * <p>
 * Probe keys are taken from the encoded row blob through {@link LogicalFieldCursor}
 * — no mid-pipeline {@code Object} re-encode.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class StrictUniqueConstraintUtil {
	private StrictUniqueConstraintUtil() {
	}

	/**
	 * Fail-closed when another row PK already owns any STRICT secondary unique of {@code valueBytes}.
	 */
	public static void validateSecondaryStrict(
			TableStore store,
			TableSchema schema,
			byte[] valueBytes,
			byte[] keyBytes
	) {
		final List<IndexDef> indexes = schema.indexes();
		if (indexes.isEmpty()) {
			return;
		}
		final String pkName = schema.pkColumn().name();
		final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, valueBytes);
		for (int i = 0, n = indexes.size(); i < n; i++) {
			final IndexDef idx = indexes.get(i);
			if (idx.kind() != IndexType.STRICT) {
				continue;
			}
			final List<String> columns = idx.columns();
			final int colCount = columns.size();
			if (colCount == 1 && columns.getFirst().equalsIgnoreCase(pkName)) {
				continue;
			}
			if (!store.hasEqIndex(columns)) {
				continue;
			}
			final byte[][] want = wireEqKeys(cursor, schema, columns);
			if (want == null) {
				continue;
			}
			store.forEachEqKey(columns, want, hit -> {
				if (hit != null && !Arrays.equals(hit, keyBytes)) {
					throw new NonUniqueValueException(
							NonUniqueValueException.formatMessage(schema.catalogKey(), idx.name(), "row"));
				}
			});
		}
	}

	/**
	 * @return wire EQ probe keys, or {@code null} when any UNIQUE column is null (SQL multi-null OK)
	 */
	private static byte[][] wireEqKeys(LogicalFieldCursor cursor, TableSchema schema, List<String> columns) {
		final int colCount = columns.size();
		final byte[][] want = new byte[colCount][];
		for (int i = 0; i < colCount; i++) {
			final int ordinal = schema.requireColumn(columns.get(i)).ordinal();
			final WireSpan span = cursor.indexKeySpan(ordinal);
			if (span == null || span.isNullWire()) {
				return null;
			}
			want[i] = span.toOwnedBytes();
		}
		return want;
	}
}
