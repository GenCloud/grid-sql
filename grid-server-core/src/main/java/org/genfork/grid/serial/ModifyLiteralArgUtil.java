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

import org.genfork.grid.replication.codec.ModifyPayload;

import java.math.BigDecimal;

/**
 * Encode / decode {@link ModifyPayload#KIND_LITERAL_SET} args without magic strings at call sites.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class ModifyLiteralArgUtil {
	private ModifyLiteralArgUtil() {
	}

	/**
	 * Canonical arg for a SET literal (SPI / parse Object to Decoded.arg).
	 */
	public static String encode(Object value) {
		if (value == null) {
			return ModifyPayload.LITERAL_SET_ARG_NULL;
		}
		if (value instanceof BigDecimal bd) {
			return bd.toPlainString();
		}
		return String.valueOf(value);
	}

	/**
	 * Restore a SET literal for field apply by declared field type.
	 */
	public static Object decode(String arg, Class<?> fieldType) {
		if (arg == null || ModifyPayload.LITERAL_SET_ARG_NULL.equals(arg)) {
			return null;
		}
		return FieldValueCoercion.coerce(arg, fieldType);
	}
}
