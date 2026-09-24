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
package org.genfork.grid.codec.duplex;

/**
 * Length-preserving quartet transform: each byte treated as four 2-bit symbols.
 * Pack = rotate symbols left by one (2 bits); unpack reverses.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class QuartetPacker {
	private QuartetPacker() {
	}

	public static byte[] pack(byte[] logical) {
		final byte[] out = new byte[logical.length];
		for (int i = 0; i < logical.length; i++) {
			final int v = logical[i] & 0xFF;
			out[i] = (byte) (((v << 2) | (v >>> 6)) & 0xFF);
		}
		return out;
	}

	public static byte[] unpack(byte[] packed, int logicalLen) {
		final byte[] out = new byte[logicalLen];
		final int n = Math.min(logicalLen, packed.length);
		for (int i = 0; i < n; i++) {
			final int v = packed[i] & 0xFF;
			out[i] = (byte) (((v >>> 2) | (v << 6)) & 0xFF);
		}
		return out;
	}
}
