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
 * Equality / hash wrapper for an <em>owned</em> wire field copy.
 * <p>
 * Prefer {@link WireSpan} mid-pipeline (zero-copy). Use this when the caller already
 * holds an owned {@code byte[]} ({@link SqlWireUtil#toGenericArray}, persist boundary).
 * Hash/equals share {@link WireRangeOps} with {@link WireSpan}.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class WireFieldBytes {
	private final byte[] bytes;
	private final int hash;

	public WireFieldBytes(byte[] bytes) {
		this.bytes = bytes == null ? SqlWireUtil.getNullPtr() : bytes;
		this.hash = WireRangeOps.hash(this.bytes, 0, this.bytes.length);
	}

	/**
	 * Owned copy of a zero-copy span (store / emit boundary).
	 */
	public static WireFieldBytes copyOf(WireSpan span) {
		if (span == null || span.isNullWire()) {
			return new WireFieldBytes(null);
		}
		return new WireFieldBytes(span.toOwnedBytes());
	}

	public byte[] bytes() {
		return bytes;
	}

	/**
	 * Zero-copy view over this owned array.
	 */
	public WireSpan asSpan() {
		return WireSpan.ofOwned(bytes);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o instanceof WireFieldBytes other) {
			return WireRangeOps.equals(bytes, 0, bytes.length, other.bytes, 0, other.bytes.length);
		}
		if (o instanceof WireSpan span) {
			return WireRangeOps.equals(
					bytes, 0, bytes.length, span.blob(), span.offset(), span.length());
		}
		return false;
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public String toString() {
		return "WireFieldBytes[len=" + bytes.length + ",hash=" + hash + "]";
	}
}
