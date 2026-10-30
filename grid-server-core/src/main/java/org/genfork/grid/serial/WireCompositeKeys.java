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

/**
 * FIELD_SEP–joined composite wire keys (GROUP BY, JOIN probe, composite index fields).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class WireCompositeKeys {
	/** Separator between owned index-key parts (trailing sep after each part). */
	public static final byte FIELD_SEP = 0x1F;

	private static final byte[] EMPTY = new byte[0];

	private WireCompositeKeys() {
	}

	/**
	 * Concatenate owned parts with {@link #FIELD_SEP} after each part (including trailing sep).
	 */
	public static byte[] joinOwnedParts(byte[][] parts) {
		if (parts == null || parts.length == 0) {
			return EMPTY;
		}
		int total = 0;
		for (byte[] part : parts) {
			total += part.length + 1;
		}
		final byte[] composite = new byte[total];
		int pos = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, composite, pos, part.length);
			pos += part.length;
			composite[pos++] = FIELD_SEP;
		}
		return composite;
	}

	/** Owned composite key as {@link WireFieldBytes}. */
	public static WireFieldBytes joinToWireField(byte[][] parts) {
		return new WireFieldBytes(joinOwnedParts(parts));
	}
}
