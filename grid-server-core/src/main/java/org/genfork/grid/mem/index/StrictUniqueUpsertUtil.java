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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.TreeKey;

/**
 * STRICT secondary-index upsert: idempotent same-PK reindex and unique-owner replace.
 * <p>
 * Quorum-acked UPSERT owns the UNIQUE slot; a prior ghost PK posting is returned for map tombstone.
 * STRICT trees hold at most one owner — no ThreadLocal side channel.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class StrictUniqueUpsertUtil {
	private static final int DISPLACED_INITIAL_CAPACITY = 2;

	private StrictUniqueUpsertUtil() {
	}

	/**
	 * Insert {@code treeKey} → {@code newPointer}. On STRICT collision, clear the slot and re-insert.
	 *
	 * @return prior foreign row PK that owned the slot, or {@code null} when none (happy / same-PK)
	 */
	public static <K, T extends TreeKey<K>> byte[] insertOrReplaceOwner(
			AbstractIndexOperation<K, T> tree,
			T treeKey,
			IndexPointerRef newPointer
	) {
		try {
			tree.insert(treeKey, newPointer);
			return null;
		} catch (NonUniqueValueException ignored) {
			final byte[] foreignPk = firstForeignOwner(tree, treeKey, newPointer.resolveKey());
			tree.delete(treeKey);
			tree.insert(treeKey, newPointer);
			return foreignPk;
		}
	}

	private static <K, T extends TreeKey<K>> byte[] firstForeignOwner(
			AbstractIndexOperation<K, T> tree,
			T treeKey,
			byte[] newPk
	) {
		final IndexOperationResult found = tree.searchEq(treeKey);
		if (found == null) {
			return null;
		}
		final Set<IndexPointerRef> pointers = found.getPointers();
		if (pointers == null || pointers.isEmpty()) {
			return null;
		}
		for (IndexPointerRef ptr : pointers) {
			if (ptr == null) {
				continue;
			}
			final byte[] oldPk = ptr.resolveKey();
			if (oldPk == null) {
				continue;
			}
			if (newPk != null && Arrays.equals(oldPk, newPk)) {
				continue;
			}
			return oldPk;
		}
		return null;
	}

	/**
	 * Append a displaced PK into an accumulator; allocates only on first displacement.
	 *
	 * @return accumulator (possibly newly created)
	 */
	public static List<byte[]> appendDisplaced(List<byte[]> acc, byte[] displacedPk) {
		if (displacedPk == null) {
			return acc;
		}
		if (acc == null) {
			final ArrayList<byte[]> created = new ArrayList<>(DISPLACED_INITIAL_CAPACITY);
			created.add(displacedPk);
			return created;
		}
		for (int i = 0, n = acc.size(); i < n; i++) {
			if (Arrays.equals(acc.get(i), displacedPk)) {
				return acc;
			}
		}
		acc.add(displacedPk);
		return acc;
	}
}
