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

import com.google.common.annotations.VisibleForTesting;

/**
 * Zero-copy view of a wire field span inside an owning row blob.
 * <p>
 * Lifetime must not exceed the owning {@code byte[]} (JOIN / snapshot working set).
 * Use {@link #toOwnedBytes()} only at store / index persist boundaries.
 * Equality and hash match {@link WireFieldBytes} / {@link java.util.Arrays#hashCode(byte[])}.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class WireSpan {
	private final byte[] blob;
	private final int offset;
	private final int length;
	private final int hash;

	private WireSpan(byte[] blob, int offset, int length, int hash) {
		this.blob = blob;
		this.offset = offset;
		this.length = length;
		this.hash = hash;
	}

	/**
	 * Span over {@code blob[offset .. offset+length)} (no copy).
	 */
	public static WireSpan of(byte[] blob, int offset, int length) {
		if (blob == null) {
			return nullSpan();
		}
		if (offset < 0 || length < 0 || offset + length > blob.length) {
			throw new IllegalArgumentException("wire span out of bounds");
		}
		return new WireSpan(blob, offset, length, WireRangeOps.hash(blob, offset, length));
	}

	/**
	 * Span covering an entire owned array (still no extra copy of content).
	 */
	public static WireSpan ofOwned(byte[] owned) {
		if (owned == null) {
			return nullSpan();
		}
		return of(owned, 0, owned.length);
	}

	/**
	 * Null wire sentinel (same bytes as {@link SqlWireUtil#getNullPtr()}).
	 */
	public static WireSpan nullSpan() {
		final byte[] nullPtr = SqlWireUtil.getNullPtr();
		return of(nullPtr, 0, nullPtr.length);
	}

	@VisibleForTesting
	public static WireSpan wrapForTest(byte[] blob, int offset, int length) {
		return of(blob, offset, length);
	}

	public byte[] blob() {
		return blob;
	}

	public int offset() {
		return offset;
	}

	public int length() {
		return length;
	}

	public boolean isNullWire() {
		final byte[] nullPtr = SqlWireUtil.getNullPtr();
		return WireRangeOps.equals(blob, offset, length, nullPtr, 0, nullPtr.length);
	}

	/**
	 * Owned copy for {@code TableStore} / BPTree key APIs.
	 */
	public byte[] toOwnedBytes() {
		if (isNullWire()) {
			return SqlWireUtil.getNullPtr();
		}
		return WireRangeOps.copyOf(blob, offset, length);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o instanceof WireSpan other) {
			return WireRangeOps.equals(blob, offset, length, other.blob, other.offset, other.length);
		}
		if (o instanceof WireFieldBytes owned) {
			final byte[] bytes = owned.bytes();
			return WireRangeOps.equals(blob, offset, length, bytes, 0, bytes.length);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public String toString() {
		return "WireSpan[off=" + offset + ",len=" + length + ",hash=" + hash + "]";
	}
}
