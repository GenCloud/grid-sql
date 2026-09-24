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
package org.genfork.grid.serial.stream;

import jodd.util.Bits;
import org.springframework.lang.NonNull;

import java.io.ObjectOutput;
import java.util.Arrays;

/**
 * Heap wire byte sink for length-prefixed encode ({@link ObjectOutput} SPI surface).
 * <p>
 * Growable {@code byte[]} cursor used by {@link org.genfork.grid.utils.SerialUtil}
 * and scalar key encode — not an Ignite OptimizedMarshaller stream.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public class WireByteSink implements ObjectOutput {
	private byte[] array;

	private int baseOffset = 0;

	public WireByteSink(int capacity) {
		array = new byte[capacity];
	}

	@Override
	public void write(byte[] b) {
		for (byte bb : b) {
			array[baseOffset++] = bb;
		}
	}

	@Override
	public void writeBoolean(boolean v) {
		Bits.putBoolean(array, baseOffset++, v);
	}

	@Override
	public void writeByte(int v) {
		array[baseOffset++] = (byte) v;
	}

	@Override
	public void writeShort(int v) {
		Bits.putShort(array, baseOffset, (short) v);
		baseOffset += 2;
	}

	@Override
	public void writeChar(int v) {
		Bits.putChar(array, baseOffset, (char) v);
		baseOffset += 2;
	}

	@Override
	public void writeInt(int v) {
		Bits.putInt(array, baseOffset, v);
		baseOffset += 4;
	}

	@Override
	public void writeFloat(float v) {
		Bits.putFloat(array, baseOffset, v);
		baseOffset += 4;
	}

	@Override
	public void writeLong(long v) {
		Bits.putLong(array, baseOffset, v);
		baseOffset += 8;
	}

	@Override
	public void writeDouble(double v) {
		Bits.putDouble(array, baseOffset, v);
		baseOffset += 8;
	}

	public byte[] toByteArray() {
		return Arrays.copyOf(array, baseOffset > 0 ? baseOffset : array.length);
	}

	/** Reuse buffer without realloc when capacity is enough. */
	public void reset(int minCapacity) {
		if (array == null || array.length < minCapacity) {
			array = new byte[Math.max(minCapacity, 64)];
		}
		baseOffset = 0;
	}

	public int position() {
		return baseOffset;
	}

	/** Direct buffer access for {@link org.genfork.grid.utils.SerialUtil} in-place encode. */
	public byte[] rawArray() {
		return array;
	}

	/** Advance write cursor after in-place encode into {@link #rawArray()}. */
	public void skip(int bytes) {
		baseOffset += bytes;
	}

	/** Ensure room for {@code extra} more bytes (grows buffer if needed). */
	public void ensureRoom(int extra) {
		final int need = baseOffset + extra;
		if (array.length < need) {
			array = Arrays.copyOf(array, Math.max(need, array.length * 2));
		}
	}

	@Override
	public void flush() {

	}

	@Override
	public void close() {
		// keep array for ThreadLocal reuse; callers use reset()
	}

	@Override
	public void writeBytes(@NonNull String s) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeObject(Object obj) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void write(int b) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void write(byte[] b, int off, int len) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeChars(@NonNull String s) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeUTF(@NonNull String s) {
		throw new UnsupportedOperationException();
	}
}
