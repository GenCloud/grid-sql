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
package org.genfork.grid.replication.codec;

import java.nio.charset.StandardCharsets;

/**
 * Structural UPDATE kind + args (planner / local merge only; OpLog stores final UPSERT bytes).
 * <p>
 * Format when encoded: kind(1) + fieldName UTF-8 + NUL + arg UTF-8.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class ModifyPayload {
	public static final byte KIND_STRING_CONCAT = 1;
	public static final byte KIND_NUMERIC_ADD = 2;
	/** Replace field with a literal (mixed UPDATE SET with RMW). */
	public static final byte KIND_LITERAL_SET = 3;

	/**
	 * Sentinel for SQL {@code NULL} in {@link #KIND_LITERAL_SET} arg encoding
	 * ({@link org.genfork.grid.serial.ModifyLiteralArgUtil}).
	 */
	public static final String LITERAL_SET_ARG_NULL = "\u0001NULL";

	private ModifyPayload() {
	}

	public static byte[] encode(byte kind, String fieldName, String arg) {
		final String f = fieldName == null ? "" : fieldName;
		final String a = arg == null ? "" : arg;
		if (f.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("fieldName must not contain NUL");
		}
		final byte[] fb = f.getBytes(StandardCharsets.UTF_8);
		final byte[] ab = a.getBytes(StandardCharsets.UTF_8);
		final byte[] out = new byte[1 + fb.length + 1 + ab.length];
		out[0] = kind;
		System.arraycopy(fb, 0, out, 1, fb.length);
		out[1 + fb.length] = 0;
		System.arraycopy(ab, 0, out, 2 + fb.length, ab.length);
		return out;
	}

	public static byte[] encodeStringConcat(String fieldName, String suffix) {
		return encode(KIND_STRING_CONCAT, fieldName, suffix);
	}

	public static byte[] encodeNumericAdd(String fieldName, long delta) {
		return encode(KIND_NUMERIC_ADD, fieldName, Long.toString(delta));
	}

	public static byte[] encodeLiteralSet(String fieldName, String encodedArg) {
		return encode(KIND_LITERAL_SET, fieldName, encodedArg);
	}

	public static Decoded decode(byte[] payload) {
		if (payload == null || payload.length < 1) {
			throw new IllegalArgumentException("empty modify payload");
		}
		final byte kind = payload[0];
		if (kind != KIND_STRING_CONCAT && kind != KIND_NUMERIC_ADD && kind != KIND_LITERAL_SET) {
			throw new IllegalArgumentException("Unknown modify kind: " + kind);
		}
		int nul = -1;
		for (int i = 1; i < payload.length; i++) {
			if (payload[i] == 0) {
				nul = i;
				break;
			}
		}
		if (nul < 0) {
			return new Decoded(kind, new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8), "");
		}
		final String field = new String(payload, 1, nul - 1, StandardCharsets.UTF_8);
		final String arg = new String(payload, nul + 1, payload.length - nul - 1, StandardCharsets.UTF_8);
		return new Decoded(kind, field, arg);
	}

	public record Decoded(byte kind, String fieldName, String arg) {
	}
}