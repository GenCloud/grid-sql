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
package org.genfork.grid.mem.index.bitmap;

import java.util.BitSet;
import java.util.function.IntConsumer;

/**
 * Compact BitSet-backed bitmap for index postings.
 *
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class Bitmap {

	private final BitSet bitSet;
	private final int size;
	private int setBitCount;

	public Bitmap(int size) {
		this.size = size;
		this.bitSet = new BitSet(size);
		this.setBitCount = 0;
	}

	public void setBit(int position) {
		if (position < 0 || position >= size) {
			throw new IndexOutOfBoundsException("Position " + position + " is out of bounds [0, " + size + ")");
		}

		if (!bitSet.get(position)) {
			bitSet.set(position);
			setBitCount++;
		}
	}

	public void clearBit(int position) {
		if (position < 0 || position >= size) {
			throw new IndexOutOfBoundsException("Position " + position + " is out of bounds [0, " + size + ")");
		}

		if (bitSet.get(position)) {
			bitSet.clear(position);
			setBitCount--;
		}
	}

	public boolean getBit(int position) {
		if (position < 0 || position >= size) {
			return false;
		}

		return bitSet.get(position);
	}

	public Bitmap and(Bitmap other) {
		if (other == null) {
			return new Bitmap(size);
		}

		final Bitmap result = new Bitmap(size);
		result.bitSet.or(this.bitSet);
		result.bitSet.and(other.bitSet);
		result.setBitCount = result.bitSet.cardinality();

		return result;
	}

	public Bitmap or(Bitmap other) {
		if (other == null) {
			return this.clone();
		}

		final Bitmap result = new Bitmap(size);
		result.bitSet.or(this.bitSet);
		result.bitSet.or(other.bitSet);
		result.setBitCount = result.bitSet.cardinality();

		return result;
	}

	public Bitmap not() {
		final Bitmap result = new Bitmap(size);
		result.bitSet.set(0, size);
		result.bitSet.xor(this.bitSet);
		result.setBitCount = result.bitSet.cardinality();

		return result;
	}

	public Bitmap xor(Bitmap other) {
		if (other == null) {
			return this.clone();
		}

		final Bitmap result = new Bitmap(size);
		result.bitSet.or(this.bitSet);
		result.bitSet.xor(other.bitSet);
		result.setBitCount = result.bitSet.cardinality();

		return result;
	}

	public boolean isEmpty() {
		return setBitCount == 0;
	}

	public int getSetBitCount() {
		return setBitCount;
	}

	public int getSize() {
		return size;
	}

	public double getDensity() {
		return size > 0 ? (double) setBitCount / size : 0.0;
	}

	public void forEachSetBit(IntConsumer consumer) {
		for (int i = bitSet.nextSetBit(0); i >= 0; i = bitSet.nextSetBit(i + 1)) {
			consumer.accept(i);
		}
	}

	public void forEachClearBit(IntConsumer consumer) {
		for (int i = bitSet.nextClearBit(0); i < size; i = bitSet.nextClearBit(i + 1)) {
			consumer.accept(i);
		}
	}

	@Override
	public Bitmap clone() {
		final Bitmap clone = new Bitmap(size);
		clone.bitSet.or(this.bitSet);
		clone.setBitCount = this.setBitCount;
		return clone;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}

		final Bitmap bitmap = (Bitmap) obj;
		return size == bitmap.size && bitSet.equals(bitmap.bitSet);
	}

	@Override
	public int hashCode() {
		return bitSet.hashCode() * 31 + size;
	}

	@Override
	public String toString() {
		return String.format("Bitmap{size=%d, setBits=%d, density=%.2f%%}",
				size, setBitCount, getDensity() * 100);
	}
}
