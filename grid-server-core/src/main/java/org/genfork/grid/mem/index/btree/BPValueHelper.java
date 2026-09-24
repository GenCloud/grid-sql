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

import org.genfork.grid.utils.UnsafeMemory;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public final class BPValueHelper {
	private BPValueHelper() {
	}

	public static byte[] keyFromNativeRef(long ptr) {
		if (ptr == 0) {
			return null;
		}

		final int keyLength = UnsafeMemory.readInt(ptr);
		if (keyLength == -1) {
			return null;
		}

		ptr += Integer.BYTES;

		final byte[] key = new byte[keyLength];
		UnsafeMemory.copy(null, ptr, key, UnsafeMemory.ARRAY_BASE_OFFSET, keyLength);
		return key;
	}

	public static long toNativeValueRef(byte[] key, byte[][] orderPropValues) {
		final int blockSize = computeBlockSize(key, orderPropValues);
		final long ptr = UnsafeMemory.malloc(blockSize);
		serialize(ptr, key, orderPropValues);
		return ptr;
	}

	private static int computeBlockSize(byte[] key, byte[][] orderPropValues) {
		int blockSize = 0;
		blockSize += Integer.BYTES;  // key length offset
		blockSize += key.length;  // key length
		blockSize += Integer.BYTES; // key order properties length offset

		if (orderPropValues != null) {
			for (byte[] typedArray : orderPropValues) {
				blockSize += Integer.BYTES; // value length offset
				blockSize += typedArray.length; // value length
			}
		}

		return blockSize;
	}

	private static void serialize(long ptr, byte[] key, byte[][] orderPropValues) {
		UnsafeMemory.writeInt(ptr, key.length); //  key length

		ptr += Integer.BYTES; // key length offset

		// key
		UnsafeMemory.copy(key, UnsafeMemory.ARRAY_BASE_OFFSET, null, ptr, key.length);

		ptr += key.length; // key length

		if (orderPropValues != null) {
			// key order properties length
			UnsafeMemory.writeInt(ptr, orderPropValues.length);

			ptr += Integer.BYTES; // key order properties length offset

			for (byte[] value : orderPropValues) {
				UnsafeMemory.writeInt(ptr, value.length); // value length

				ptr += Integer.BYTES; // value length offset

				// value
				UnsafeMemory.copy(value, UnsafeMemory.ARRAY_BASE_OFFSET, null, ptr, value.length);

				ptr += value.length;
			}
		} else {
			// key order properties length
			UnsafeMemory.writeInt(ptr, 0);
		}
	}
}
