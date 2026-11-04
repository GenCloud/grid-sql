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
package org.genfork.grid.mem.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.CompositeIndexCondition;
import org.genfork.grid.query.filters.impl.NotCondition;
import org.genfork.grid.query.filters.impl.OrCondition;
import org.genfork.grid.serial.FieldMetaData;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.WireCompositeKeys;
import org.genfork.grid.serial.WireFieldBytes;

/**
 * Static plan/probe helpers for {@link GridCompositeIndex}.
 * <p>
 * Column match, composite-index tree walk, and aggregate wire formatting — no bitmap
 * opt-in policy changes.
 *
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
final class CompositeIndexProbeOps {

	private CompositeIndexProbeOps() {
	}

	/**
	 * True when {@code prefixColumns} is a strict left-prefix of {@code fullColumns}
	 * (ordered, case-insensitive). Equal-length lists are not a prefix (exact match elsewhere).
	 */
	static boolean isLeftPrefixIgnoreCaseOrdered(List<String> fullColumns, List<String> prefixColumns) {
		if (fullColumns == null || prefixColumns == null || prefixColumns.isEmpty()) {
			return false;
		}
		if (prefixColumns.size() >= fullColumns.size()) {
			return false;
		}
		for (int i = 0; i < prefixColumns.size(); i++) {
			if (!fullColumns.get(i).equalsIgnoreCase(prefixColumns.get(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Walk a filter tree for the first {@link CompositeIndexCondition} index op.
	 */
	static AbstractIndexOperation<byte[][], CompositeTreeKey> findCompositeIndexInTree(
			FilterCondition node
	) {
        switch (node) {
            case null -> {
                return null;
            }
            case CompositeIndexCondition compositeIndexCondition -> {
                return compositeIndexCondition.indexOperation();
            }
            case AndCondition and -> {
                final AbstractIndexOperation<byte[][], CompositeTreeKey> left =
                        findCompositeIndexInTree(and.getLeft());
                if (left != null) {
                    return left;
                }
                return findCompositeIndexInTree(and.getRight());
            }
            case OrCondition or -> {
                final AbstractIndexOperation<byte[][], CompositeTreeKey> left =
                        findCompositeIndexInTree(or.getLeft());
                if (left != null) {
                    return left;
                }
                return findCompositeIndexInTree(or.getRight());
            }
            case NotCondition not -> {
                return findCompositeIndexInTree(not.getChild());
            }
            default -> {
            }
        }
        return null;
	}

	/** Wire field bytes for a catalog column (null-safe). */
	static WireFieldBytes readFieldBytes(byte[] valueBytes, TableSchema schema, FieldMetaData field) {
		if (valueBytes == null || schema == null || field == null) {
			return new WireFieldBytes(null);
		}
		final ColumnDef col = schema.column(field.getName());
		if (col == null) {
			return new WireFieldBytes(null);
		}
		return new WireFieldBytes(LogicalFieldCursor.open(schema, valueBytes).indexKeyBytes(col.ordinal()));
	}

	/** Composite GROUP BY wire key from multiple catalog columns (FIELD_SEP joined). */
	static WireFieldBytes readCompositeFieldBytes(
			byte[] valueBytes,
			TableSchema schema,
			List<FieldMetaData> fields
	) {
		if (fields == null || fields.isEmpty()) {
			return new WireFieldBytes(null);
		}
		if (fields.size() == 1) {
			return readFieldBytes(valueBytes, schema, fields.getFirst());
		}
		final LogicalFieldCursor cur = LogicalFieldCursor.open(schema, valueBytes);
		final byte[][] parts = new byte[fields.size()][];
		for (int i = 0; i < fields.size(); i++) {
			final ColumnDef col = schema.column(fields.get(i).getName());
			if (col == null) {
				parts[i] = SqlWireUtil.getNullPtr();
			} else {
				parts[i] = cur.indexKeyBytes(col.ordinal());
			}
		}
		return WireCompositeKeys.joinToWireField(parts);
	}

	/** Format GROUP BY count rows as key/tab/count UTF-8 lines. */
	static List<byte[]> formatAggRows(Map<WireFieldBytes, Long> counts) {
		final List<byte[]> out = new ArrayList<>(counts.size());
		for (Map.Entry<WireFieldBytes, Long> e : counts.entrySet()) {
			final String line = e.getKey().toString() + '\t' + e.getValue();
			out.add(line.getBytes(StandardCharsets.UTF_8));
		}
		return out;
	}
}
