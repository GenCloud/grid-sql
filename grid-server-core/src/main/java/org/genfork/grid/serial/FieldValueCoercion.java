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

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Coerce scalars into a field's declared type for structural modify / SQL bind.
 * <p>
 * Keeps merge/apply free of per-primitive if-ladders at call sites.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class FieldValueCoercion {
	private FieldValueCoercion() {
	}

	public static Object coerce(Object raw, Class<?> fieldType) {
		if (raw == null) {
			return null;
		}
		if (fieldType == String.class) {
			return String.valueOf(raw);
		}
		if (fieldType.isInstance(raw)) {
			return raw;
		}
		if (fieldType == boolean.class || fieldType == Boolean.class) {
			if (raw instanceof Boolean b) {
				return b;
			}
			return Boolean.parseBoolean(String.valueOf(raw));
		}
		if (isNumeric(fieldType)) {
			final Number n = toNumber(raw);
			return boxNumber(n, fieldType);
		}
		if (fieldType == char.class || fieldType == Character.class) {
			final String s = String.valueOf(raw);
			if (s.isEmpty()) {
				return fieldType.isPrimitive() ? Character.valueOf((char) 0) : null;
			}
			return Character.valueOf(s.charAt(0));
		}
		throw new IllegalArgumentException("Cannot coerce " + raw.getClass().getName()
				+ " to field type " + fieldType.getName());
	}

	/** Read numeric field as long (null/absent -> 0). */
	public static long asLong(Object prev) {
		if (prev == null) {
			return 0L;
		}
		if (prev instanceof Number n) {
			return n.longValue();
		}
		return Long.parseLong(String.valueOf(prev));
	}

	/** Read numeric field as double (null/absent -> 0). */
	public static double asDouble(Object prev) {
		if (prev == null) {
			return 0d;
		}
		if (prev instanceof Number n) {
			return n.doubleValue();
		}
		return Double.parseDouble(String.valueOf(prev));
	}


	private static boolean isNumeric(Class<?> fieldType) {
		return fieldType == byte.class || fieldType == Byte.class
				|| fieldType == short.class || fieldType == Short.class
				|| fieldType == int.class || fieldType == Integer.class
				|| fieldType == long.class || fieldType == Long.class
				|| fieldType == float.class || fieldType == Float.class
				|| fieldType == double.class || fieldType == Double.class
				|| fieldType == BigInteger.class
				|| fieldType == BigDecimal.class
				|| Number.class.isAssignableFrom(fieldType);
	}

	private static Number toNumber(Object raw) {
		if (raw instanceof Number n) {
			return n;
		}
		final String s = String.valueOf(raw).trim();
		if (s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
			return Double.valueOf(s);
		}
		return Long.valueOf(s);
	}

	private static Object boxNumber(Number n, Class<?> fieldType) {
		if (fieldType == byte.class || fieldType == Byte.class) {
			return Byte.valueOf(n.byteValue());
		}
		if (fieldType == short.class || fieldType == Short.class) {
			return Short.valueOf(n.shortValue());
		}
		if (fieldType == int.class || fieldType == Integer.class) {
			return Integer.valueOf(n.intValue());
		}
		if (fieldType == long.class || fieldType == Long.class) {
			return Long.valueOf(n.longValue());
		}
		if (fieldType == float.class || fieldType == Float.class) {
			return Float.valueOf(n.floatValue());
		}
		if (fieldType == double.class || fieldType == Double.class) {
			return Double.valueOf(n.doubleValue());
		}
		if (fieldType == BigInteger.class) {
			if (n instanceof BigInteger bi) {
				return bi;
			}
			if (n instanceof BigDecimal bd) {
				return bd.toBigInteger();
			}
			return BigInteger.valueOf(n.longValue());
		}
		if (fieldType == BigDecimal.class) {
			if (n instanceof BigDecimal bd) {
				return bd;
			}
			if (n instanceof BigInteger bi) {
				return new BigDecimal(bi);
			}
			if (n instanceof Float || n instanceof Double) {
				return BigDecimal.valueOf(n.doubleValue());
			}
			return BigDecimal.valueOf(n.longValue());
		}
		throw new IllegalArgumentException("Unsupported numeric field type: " + fieldType.getName());
	}
}