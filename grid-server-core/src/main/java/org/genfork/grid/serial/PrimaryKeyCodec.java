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
package org.genfork.grid.serial;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;

/**
 * Encodes ordered SQL PRIMARY KEY values into internal wire bytes.
 * <p>
 * Single-column keys retain their legacy scalar format. Composite keys use
 * little-endian length-prefixed components and are never decoded in lookup paths.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class PrimaryKeyCodec {
	private static final int LENGTH_BYTES = Integer.BYTES;
	private static final ByteOrder WIRE_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

	private PrimaryKeyCodec() {
	}

	public static byte[] encodeRow(TableSchema schema, Object[] row) {
		final List<ColumnDef> pkColumns = schema.pkColumns();
		if (pkColumns.size() == 1) {
			return SqlWireUtil.toGenericArray(row[pkColumns.getFirst().ordinal()]);
		}
		final Object[] values = new Object[pkColumns.size()];
		for (int i = 0; i < pkColumns.size(); i++) {
			values[i] = row[pkColumns.get(i).ordinal()];
		}
		return encodeValues(schema, values);
	}

	public static byte[] encodeValues(TableSchema schema, Object[] values) {
		final List<ColumnDef> pkColumns = schema.pkColumns();
		if (values.length != pkColumns.size()) {
			throw new IllegalArgumentException(
					"PRIMARY KEY expects " + pkColumns.size() + " values, got " + values.length);
		}
		if (pkColumns.size() == 1) {
			return SqlWireUtil.toGenericArray(RowEncoder.coerce(pkColumns.getFirst(), values[0]));
		}
		final byte[][] components = new byte[values.length][];
		int totalBytes = 0;
		for (int i = 0; i < values.length; i++) {
			final Object value = RowEncoder.coerce(pkColumns.get(i), values[i]);
			components[i] = SqlWireUtil.toGenericArray(value);
			totalBytes = Math.addExact(totalBytes, Math.addExact(LENGTH_BYTES, components[i].length));
		}
		final ByteBuffer out = ByteBuffer.allocate(totalBytes).order(WIRE_BYTE_ORDER);
		for (byte[] component : components) {
			out.putInt(component.length);
			out.put(component);
		}
		return out.array();
	}

	public static byte[] encodeArgument(TableSchema schema, Object value) {
		if (schema.pkColumns().size() == 1) {
			return encodeValues(schema, new Object[]{value});
		}
		if (value instanceof Object[] values) {
			return encodeValues(schema, values);
		}
		if (value instanceof List<?> values) {
			return encodeValues(schema, values.toArray());
		}
		throw new IllegalArgumentException("Composite PRIMARY KEY requires Object[] or List values");
	}
}
