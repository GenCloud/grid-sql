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
package org.genfork.grid.mem.index;

import java.time.temporal.Temporal;

import org.genfork.grid.utils.SerialUtil;
import org.genfork.grid.utils.UnsafeMemory;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public enum ArrayDataType {
	BYTE(new PrimitiveComparator()),
	SHORT(new PrimitiveComparator()),
	INT(new PrimitiveComparator()),
	LONG(new PrimitiveComparator()),
	DOUBLE(new PrimitiveComparator()),
	FLOAT(new PrimitiveComparator()),
	STRING(new StringByteArrayComparator());

	private final Cmp comparator;

	ArrayDataType(Cmp comparator) {
		this.comparator = comparator;
	}

	public Cmp getComparator() {
		return comparator;
	}

	public static ArrayDataType of(Class<?> clazz) {
		if (clazz == Boolean.class || clazz == Boolean.TYPE) {
			return ArrayDataType.BYTE;
		} else if (clazz == Byte.class || clazz == Byte.TYPE) {
			return ArrayDataType.BYTE;
		} else if (clazz == Short.class || clazz == Short.TYPE) {
			return ArrayDataType.SHORT;
		} else if (clazz == Integer.class || clazz == Integer.TYPE) {
			return ArrayDataType.INT;
		} else if (clazz == Long.class || clazz == Long.TYPE) {
			return ArrayDataType.LONG;
		} else if (clazz == Double.class || clazz == Double.TYPE) {
			return ArrayDataType.DOUBLE;
		} else if (clazz == Float.class || clazz == Float.TYPE) {
			return ArrayDataType.FLOAT;
		} else if (clazz == String.class || Temporal.class.isAssignableFrom(clazz)) {
			return ArrayDataType.STRING;
		}

		throw new UnsupportedOperationException("Unknown data type passed " + clazz);
	}

	public interface Cmp {
		static long getValuePtrByIndex(long ptr, int index) {
			ptr += UnsafeMemory.readInt(ptr) + Integer.BYTES; // key length/data

			final int mapSize = UnsafeMemory.readInt(ptr);
			ptr += Integer.BYTES;

			if (index >= mapSize) {
				return -1;
			}

			if (index == 0) {
				return ptr;
			}

			for (int idx = 0; idx < mapSize; idx++) {
				if (idx == index) {
					return ptr;
				}

				ptr += UnsafeMemory.readInt(ptr) + Integer.BYTES; // value length/data
			}

			return -1;
		}

		int compare(long locPtr1, long locPtr2, int index);
	}

	static class StringByteArrayComparator implements Cmp {
		@Override
		public int compare(long locPtr1, long locPtr2, int index) {
			long valuePtr1 = Cmp.getValuePtrByIndex(locPtr1, index);
			long valuePtr2 = Cmp.getValuePtrByIndex(locPtr2, index);
			if (valuePtr1 == -1 || valuePtr1 == 0) {
				return -1;
			}

			if (valuePtr2 == -1 || valuePtr2 == 0) {
				return 1;
			}

			// Order prop blob: [blobLen LE][UTF-8 string wire: u32 BE len | bytes]
			final int blobLen1 = UnsafeMemory.readInt(valuePtr1);
			final int blobLen2 = UnsafeMemory.readInt(valuePtr2);
			valuePtr1 += Integer.BYTES;
			valuePtr2 += Integer.BYTES;

			if (blobLen1 < 4 || blobLen2 < 4) {
				return Integer.compare(blobLen1, blobLen2);
			}

			final int strLen1 = readIntBE(valuePtr1);
			final int strLen2 = readIntBE(valuePtr2);
			if (strLen1 == SerialUtil.STRING_NULL) {
				return strLen2 == SerialUtil.STRING_NULL ? 0 : -1;
			}
			if (strLen2 == SerialUtil.STRING_NULL) {
				return 1;
			}
			valuePtr1 += Integer.BYTES;
			valuePtr2 += Integer.BYTES;

			final int length = Math.min(Math.max(0, strLen1), Math.max(0, strLen2));
			for (int offset = 0; offset < length; offset++) {
				final int b1 = UnsafeMemory.readByte(valuePtr1 + offset) & 0xFF;
				final int b2 = UnsafeMemory.readByte(valuePtr2 + offset) & 0xFF;
				final int diff = Integer.compare(b1, b2);
				if (diff != 0) {
					return diff;
				}
			}

			return Integer.compare(strLen1, strLen2);
		}

		private static int readIntBE(long addr) {
			return ((UnsafeMemory.readByte(addr) & 0xFF) << 24)
					| ((UnsafeMemory.readByte(addr + 1) & 0xFF) << 16)
					| ((UnsafeMemory.readByte(addr + 2) & 0xFF) << 8)
					| (UnsafeMemory.readByte(addr + 3) & 0xFF);
		}
	}

	static class PrimitiveComparator implements Cmp {
		@Override
		public int compare(long locPtr1, long locPtr2, int index) {
			long valuePtr1 = Cmp.getValuePtrByIndex(locPtr1, index);
			long valuePtr2 = Cmp.getValuePtrByIndex(locPtr2, index);
			if (valuePtr1 == -1 || valuePtr1 == 0) {
				return -1;
			}

			if (valuePtr2 == -1 || valuePtr2 == 0) {
				return 1;
			}

			final int valueLength1 = UnsafeMemory.readInt(valuePtr1);
			final int valueLength2 = UnsafeMemory.readInt(valuePtr2);

			valuePtr1 += Integer.BYTES;
			valuePtr2 += Integer.BYTES;

			final int length = Math.min(valueLength1, valueLength2);
			for (int offset = 0; offset < length; offset++) {
				final int b1 = UnsafeMemory.readByte(valuePtr1 + offset) & 0xFF;
				final int b2 = UnsafeMemory.readByte(valuePtr2 + offset) & 0xFF;

				final int diff = Integer.compare(b1, b2);
				if (diff != 0) {
					return diff;
				}
			}

			return Integer.compare(valueLength1, valueLength2);
		}
	}
}
