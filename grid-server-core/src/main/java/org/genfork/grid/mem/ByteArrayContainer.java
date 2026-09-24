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
package org.genfork.grid.mem;

import org.genfork.grid.utils.ArrayUtil;

/**
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public class ByteArrayContainer {
	private final byte[] array;
	private final int hashCode;

	public ByteArrayContainer(byte[] array) {
		this.array = array;
		hashCode = ArrayUtil.fastHash(array);
	}

	public byte[] getArray() {
		return array;
	}

	@SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}

		if (object == null) {
			return false;
		}

		final ByteArrayContainer that = (ByteArrayContainer) object;

		if (array == that.array) {
			return true;
		}

		final int length = array.length;
		if (that.array.length != length) {
			return false;
		}

		for (int i = 0; i < array.length; i++) {
			if (array[i] != that.array[i]) {
				return false;
			}
		}

		return true;
	}

	@Override
	public int hashCode() {
		return hashCode;
	}
}
