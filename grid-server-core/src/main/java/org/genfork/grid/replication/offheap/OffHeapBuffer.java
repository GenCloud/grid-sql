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
package org.genfork.grid.replication.offheap;

import one.nio.util.Cleaner;
import org.genfork.grid.utils.UnsafeMemory;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * Off-heap byte cursor backed by {@link UnsafeMemory#malloc(long)} or a mapped address.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class OffHeapBuffer implements AutoCloseable {
	private static final long ADDRESS_FIELD_OFFSET;

	static {
		try {
			final Field address = Buffer.class.getDeclaredField("address");
			ADDRESS_FIELD_OFFSET = UnsafeMemory.objectFieldOffset(address);
		} catch (NoSuchFieldException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private long address;
	private long capacity;
	private long position;
	private final boolean owned;
	private final ByteBuffer keepAlive;
	private boolean closed;

	private OffHeapBuffer(long address, long capacity, boolean owned, ByteBuffer keepAlive) {
		this.address = address;
		this.capacity = capacity;
		this.owned = owned;
		this.keepAlive = keepAlive;
		this.position = 0L;
		if (owned) {
			new Cleaner(this) {
				@Override
				public void clear() {
					OffHeapBuffer.this.freeOwned();
				}
			};
		}
	}

	public static OffHeapBuffer allocate(long capacity) {
		final long addr = UnsafeMemory.malloc(capacity);
		for (long i = 0; i < capacity; i++) {
			UnsafeMemory.writeByte(addr + i, (byte) 0);
		}
		return new OffHeapBuffer(addr, capacity, true, null);
	}

	public static OffHeapBuffer wrapMappedAddress(long address, long capacity, ByteBuffer keepAlive) {
		return new OffHeapBuffer(address, capacity, false, keepAlive);
	}

	public static long addressOf(ByteBuffer buffer) {
		if (!buffer.isDirect()) {
			throw new IllegalArgumentException("direct buffer required");
		}
		return UnsafeMemory.getUnsafe().getLong(buffer, ADDRESS_FIELD_OFFSET);
	}

	public long address() {
		return address;
	}

	public long capacity() {
		return capacity;
	}

	public long position() {
		return position;
	}

	public void position(long position) {
		ensureOpen();
		if (position < 0 || position > capacity) {
			throw new IllegalArgumentException("position out of bounds: " + position);
		}
		this.position = position;
	}

	public void putByte(byte v) {
		ensureSpace(1);
		UnsafeMemory.writeByte(address + position, v);
		position += 1;
	}

	public void putInt(int v) {
		ensureSpace(4);
		UnsafeMemory.writeInt(address + position, v);
		position += 4;
	}

	public void putLong(long v) {
		ensureSpace(8);
		UnsafeMemory.writeLong(address + position, v);
		position += 8;
	}

	public void putBytes(byte[] data) {
		if (data == null || data.length == 0) {
			return;
		}
		ensureSpace(data.length);
		UnsafeMemory.copy(data, UnsafeMemory.ARRAY_BASE_OFFSET, null, address + position, data.length);
		position += data.length;
	}

	public void putBytes(long srcAddr, int len) {
		if (len <= 0) {
			return;
		}
		ensureSpace(len);
		UnsafeMemory.copy(null, srcAddr, null, address + position, len);
		position += len;
	}

	public byte getByte() {
		ensureReadable(1);
		final byte v = UnsafeMemory.readByte(address + position);
		position += 1;
		return v;
	}

	public int getInt() {
		ensureReadable(4);
		final int v = UnsafeMemory.readInt(address + position);
		position += 4;
		return v;
	}

	public long getLong() {
		ensureReadable(8);
		final long v = UnsafeMemory.readLong(address + position);
		position += 8;
		return v;
	}

	public byte[] getBytes(int len) {
		ensureReadable(len);
		final byte[] data = new byte[len];
		UnsafeMemory.copy(null, address + position, data, UnsafeMemory.ARRAY_BASE_OFFSET, len);
		position += len;
		return data;
	}

	private void ensureSpace(int bytes) {
		ensureOpen();
		if (position + bytes > capacity) {
			throw new IllegalStateException("OffHeapBuffer overflow pos=" + position + " need=" + bytes + " cap=" + capacity);
		}
	}

	private void ensureReadable(int bytes) {
		ensureOpen();
		if (position + bytes > capacity) {
			throw new IllegalStateException("OffHeapBuffer underflow pos=" + position + " need=" + bytes + " cap=" + capacity);
		}
	}

	private void ensureOpen() {
		if (closed || address == 0L) {
			throw new IllegalStateException("OffHeapBuffer closed");
		}
	}

	private void freeOwned() {
		if (owned && address != 0L) {
			UnsafeMemory.free(address);
			address = 0L;
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		freeOwned();
	}
}
