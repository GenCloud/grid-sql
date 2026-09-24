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
import org.genfork.grid.utils.ArrayUtil;
import org.genfork.grid.utils.SerialUtil;
import org.springframework.lang.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class SingleTreeKey implements TreeKey<byte[]> {
	private static final ArraysComparator COMPARATOR = new ArraysComparator();

	private final byte[] key;
	private final int hashCode;

	public SingleTreeKey(byte[] key) {
		this.key = Objects.requireNonNull(key);
		hashCode = ArrayUtil.fastHash(key);
	}

	@Override
	public byte[] getKey() {
		return key;
	}

	@Override
	public int size() {
		return key.length;
	}

	@Override
	public TreeKey<byte[]> getPrefix(int length) {
		return this;
	}

	@Override
	public boolean isPartial() {
		return false;
	}

	@Override
	public int compareFull(@Nullable TreeKey<byte[]> o) {
		if (o == null) {
			return 1;
		}

		return COMPARATOR.compare(key, o.getKey());
	}

	@Override
	public int comparePartial(@Nullable TreeKey<byte[]> o) {
		if (o == null) {
			return 1;
		}

		return COMPARATOR.compare(key, o.getKey());
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		if (!(o instanceof SingleTreeKey singleKey)) {
			return false;
		}

		return Arrays.equals(key, singleKey.key);
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	@Override
	public String toString() {
		return "SingleTreeKey{" +
				"key=" + SerialUtil.readUtf8(key, 0) +
				", hashCode=" + hashCode +
				'}';
	}
}
