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
package org.genfork.grid.replication.apply;

import org.genfork.grid.replication.codec.ModifyPayload;
import org.genfork.grid.serial.FieldValueCoercion;
import org.genfork.grid.serial.ModifyLiteralArgUtil;

import java.math.BigDecimal;

/**
 * Applies {@link ModifyPayload} kinds to bare field values.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class FieldModifyApplicator {
	private FieldModifyApplicator() {
	}

	public static Object applyValue(Class<?> fieldType, byte kind, String arg, Object prev) {
		if (kind == ModifyPayload.KIND_STRING_CONCAT) {
			if (fieldType != String.class) {
				throw new IllegalArgumentException("STRING_CONCAT requires String field");
			}
			final String p = prev == null ? "" : String.valueOf(prev);
			final String add = arg == null ? "" : arg;
			return p + add;
		}
		if (kind == ModifyPayload.KIND_NUMERIC_ADD) {
			if (fieldType == float.class || fieldType == Float.class
					|| fieldType == double.class || fieldType == Double.class
					|| fieldType == BigDecimal.class) {
				final double delta = Double.parseDouble(arg);
				return FieldValueCoercion.asDouble(prev) + delta;
			}
			final long delta = Long.parseLong(arg);
			return FieldValueCoercion.asLong(prev) + delta;
		}
		if (kind == ModifyPayload.KIND_LITERAL_SET) {
			return ModifyLiteralArgUtil.decode(arg, fieldType);
		}
		throw new IllegalArgumentException("Unknown modify kind: " + kind);
	}
}