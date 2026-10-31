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
package org.genfork.grid.replication.snapshot.sealed;

import java.util.Objects;

import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;

/**
 * Wire-key compare for sealed open-range cold-merge (single-column keys).
 * <p>
 * Same contract as RAM BPTree / {@link ArraysComparator} (signed INT/LONG, UTF-8
 * payload-lex, unsigned lex for other varlen) so cold-merge bounds match tree order.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedIndexKeyOrder {
	private static final ArraysComparator ARRAYS = new ArraysComparator();

	private SealedIndexKeyOrder() {
	}

	public static int compare(byte[] left, byte[] right) {
		Objects.requireNonNull(left, "left");
		Objects.requireNonNull(right, "right");
		return ARRAYS.compare(left, right);
	}
}
