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
package org.genfork.grid.query.filters;

import java.util.ArrayList;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.utils.ArrayVectors;

/**
 * Batch residual filter over wire blobs (SIMD EQ path + scalar fallback).
 * <p>
 * Simple single-column {@code EQ} uses {@link ArrayVectors#bytesEqual} on
 * {@link LogicalFieldCursor#indexKeyBytes}; complex trees fall back to
 * {@link FilterCondition#matches(byte[], TableSchema)}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class WireResidualBatch {
	private static final int EMPTY = 0;

	private WireResidualBatch() {
	}

	/**
	 * Keep blobs that match {@code filter} (null filter → keep all).
	 */
	public static List<byte[]> filterBlobs(
			List<byte[]> values,
			TableSchema schema,
			FilterCondition filter
	) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		if (filter == null) {
			return List.copyOf(values);
		}
		if (schema != null && isSimpleEq(filter)) {
			return filterEqSimd(values, schema, (LogicalOperatorCondition) filter);
		}
		return filterScalar(values, schema, filter);
	}

	@VisibleForTesting
	public static boolean isSimpleEq(FilterCondition filter) {
		if (!(filter instanceof LogicalOperatorCondition loc)) {
			return false;
		}
		return loc.getOperator() == LogicalOperatorCondition.Operator.EQ
				&& loc.getValues() != null
				&& loc.getValues().length == 1
				&& loc.getValues()[0] != null;
	}

	private static List<byte[]> filterEqSimd(
			List<byte[]> values,
			TableSchema schema,
			LogicalOperatorCondition eq
	) {
		final ColumnDef col = schema.column(eq.getField());
		if (col == null) {
			return filterScalar(values, schema, eq);
		}
		final byte[] expected = SqlWireUtil.toGenericArray(eq.getValues()[0]);
		final int ordinal = col.ordinal();
		final List<byte[]> out = new ArrayList<>(values.size());
		for (byte[] blob : values) {
			if (blob == null) {
				continue;
			}
			try {
				final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, blob);
				final byte[] actual = cursor.indexKeyBytes(ordinal);
				if (ArrayVectors.bytesEqual(actual, expected)) {
					out.add(blob);
				}
			} catch (RuntimeException ex) {
				// Scalar-compatible: bad cursor / missing field → reject like matches().
			}
		}
		return out;
	}

	private static List<byte[]> filterScalar(
			List<byte[]> values,
			TableSchema schema,
			FilterCondition filter
	) {
		final List<byte[]> out = new ArrayList<>(values.size());
		for (byte[] blob : values) {
			if (blob == null) {
				continue;
			}
			if (filter.matches(blob, schema)) {
				out.add(blob);
			}
		}
		return out;
	}

	/** Named empty size (tests). */
	@VisibleForTesting
	public static int emptySize() {
		return EMPTY;
	}
}
