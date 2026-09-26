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
package org.genfork.grid.mem.index.btree;

import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;
import org.genfork.grid.utils.SerialUtil;

import java.util.Arrays;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class CompositeTreeKey implements TreeKey<byte[][]> {
	private static final ArraysComparator ARRAYS_COMPARATOR = new ArraysComparator();
	private static final int NO_PARTIAL_COLUMN = -1;

	private final byte[][] key;
	private final int hashCode;

	private int cmpIndex = NO_PARTIAL_COLUMN;

	public CompositeTreeKey(byte[][] key) {
		this.key = key;
		hashCode = Arrays.deepHashCode(key);
	}

	public CompositeTreeKey(byte[][] key, int cmpIndex) {
		this.key = key;
		this.cmpIndex = cmpIndex;
		hashCode = Arrays.deepHashCode(key);
	}

	public byte[] getKey(int index) {
		return key[index];
	}

	@Override
	public byte[][] getKey() {
		return key;
	}

	@Override
	public int size() {
		return key.length;
	}

	/**
	 * Column index for single-field partial EQ ({@link #isPartial()}), or {@code -1} when full / left-prefix multi.
	 */
	public int partialColumnIndex() {
		return cmpIndex;
	}

	@Override
	public TreeKey<byte[][]> getPrefix(int length) {
		if (length <= 0 || length > key.length) {
			throw new IllegalArgumentException("Invalid prefix length: " + length);
		}

		final byte[][] prefixValues = Arrays.copyOf(key, length);
		return new CompositeTreeKey(prefixValues);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CompositeTreeKey that)) {
			return false;
		}

		return Arrays.deepEquals(key, that.key);
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	@Override
	public boolean isPartial() {
		return cmpIndex != NO_PARTIAL_COLUMN;
	}

	@Override
	public int compareFull(TreeKey<byte[][]> other) {
		if (other == null) {
			return -1;
		}

		final byte[][] otherKey = other.getKey();
		if (otherKey == null) {
			return -1;
		}

		final int minLength = Math.min(key.length, otherKey.length);
		for (int i = 0; i < minLength; i++) {
			final int cmp = ARRAYS_COMPARATOR.compare(key[i], otherKey[i]);
			if (cmp != 0) {
				return cmp;
			}
		}

		return Integer.compare(key.length, otherKey.length);
	}

	@Override
	public int comparePartial(TreeKey<byte[][]> other) {
		if (other == null) {
			return -1;
		}

		final byte[][] otherKey = other.getKey();
		if (otherKey == null) {
			return -1;
		}

		final CompositeTreeKey otherCompositeKey = (CompositeTreeKey) other;
		final int otherCmpIndex = otherCompositeKey.cmpIndex;

		if (cmpIndex != NO_PARTIAL_COLUMN) {
			// Single-column partial (createKey(field, value)): key holds one value at [0],
			// cmpIndex selects which leaf column to compare.
			if (key.length == 1) {
				if (cmpIndex >= otherKey.length) {
					return -1;
				}
				final byte[] otherValue = otherKey[cmpIndex];
				return ARRAYS_COMPARATOR.compare(key[0], otherValue);
			}
			// Left-prefix multi: key[0..len) vs otherKey[0..len); tree order is left-to-right.
			final int prefixLen = key.length;
			if (prefixLen > otherKey.length) {
				return 1;
			}
			for (int i = 0; i < prefixLen; i++) {
				final int cmp = ARRAYS_COMPARATOR.compare(key[i], otherKey[i]);
				if (cmp != 0) {
					return cmp;
				}
			}
			return 0;
		}

		if (otherCmpIndex != -1) {
			// Other key is partial — compare only the selected column
			if (otherCmpIndex >= key.length) {
				return -1;
			}

			final byte[] thisValue = key[otherCmpIndex];
			return ARRAYS_COMPARATOR.compare(otherKey[0], thisValue);
		}

		if (key.length != otherKey.length) {
			final int minLength = Math.min(key.length, otherKey.length);
			for (int i = 0; i < minLength; i++) {
				final int cmp = ARRAYS_COMPARATOR.compare(key[i], otherKey[i]);
				if (cmp != 0) {
					return cmp;
				}
			}

			return 0;
		}

		for (int i = 0; i < key.length; i++) {
			final int cmp = ARRAYS_COMPARATOR.compare(key[i], otherKey[i]);
			if (cmp != 0) {
				return cmp;
			}
		}

		return 0;
	}

	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder("CompositeTreeKey{keys=[");
		for (byte[] key : key) {
			builder.append(SerialUtil.readUtf8(key, 0)).append(",");
		}

		builder.append("], hashCode=").append(hashCode).append('}');
		return builder.toString();
	}
}
