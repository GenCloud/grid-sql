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
package org.genfork.grid.utils;

/**
 * Byte-array hash / equality / unsigned compare helpers.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public class ArrayUtil {
	public static int fastHash(byte[] array) {
		int result = 1;

		for (byte b : array) {
			result = 31 * result + b;
		}
		return result;
	}

	/**
	 * Byte equality with {@link java.util.Arrays#equals(byte[], byte[])} null semantics.
	 * <p>
	 * Delegates to {@link ArrayVectors#bytesEqual(byte[], byte[])}.
	 */
	public static boolean bytesEqual(byte[] left, byte[] right) {
		return ArrayVectors.bytesEqual(left, right);
	}

	/**
	 * Unsigned lexicographic compare.
	 * <p>
	 * Delegates to {@link ArrayVectors#compareUnsigned(byte[], byte[])}.
	 */
	public static int compareUnsigned(byte[] left, byte[] right) {
		return ArrayVectors.compareUnsigned(left, right);
	}
}
