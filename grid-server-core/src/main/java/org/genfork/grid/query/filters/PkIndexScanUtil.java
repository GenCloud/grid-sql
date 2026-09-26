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
package org.genfork.grid.query.filters;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Consumer;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.AbstractBPTree;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;

/**
 * Shared PRIMARY KEY tree resolve + scan for filter / maintenance paths.
 * <p>
 * Exact bind: single PK column → scalar map entry; multi → composite whose
 * columns match {@code pkColumnNames} order-insensitively (same contract as
 * {@code GridCompositeIndex.resolveCompositeIndex} / {@code resolveSingleColumnIndex}).
 * Leading-column heuristics are not used — a secondary with the same leading
 * column must not replace the PK tree.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class PkIndexScanUtil {
	private PkIndexScanUtil() {
	}

	/**
	 * Resolve the PRIMARY KEY index tree for {@code pkColumnNames} (exact column list).
	 *
	 * @return PK tree, or {@code null} when missing
	 */
	public static AbstractIndexOperation<?, ?> resolvePrimaryKeyIndex(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> pkColumnNames
	) {
		if (pkColumnNames == null || pkColumnNames.isEmpty()) {
			return null;
		}
		if (pkColumnNames.size() == 1) {
			return resolveSingleColumnIndex(property2Index, pkColumnNames.getFirst());
		}
		return resolveCompositeIndex(compositeIndexes, pkColumnNames);
	}

	/**
	 * Full PK posting set via {@link AbstractIndexOperation#searchAll()}.
	 */
	public static IndexOperationResult searchAllPrimaryKeys(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> pkColumnNames
	) {
		final AbstractIndexOperation<?, ?> tree =
				resolvePrimaryKeyIndex(property2Index, compositeIndexes, pkColumnNames);
		if (tree == null) {
			return IndexOperationResult.EMPTY;
		}
		return tree.searchAll();
	}

	/**
	 * Stream every row PK byte array from the PK B+ tree leaves (no HashSet materialization).
	 *
	 * @return {@code true} if the PK tree was found; {@code false} if missing
	 */
	public static boolean forEachPrimaryKeyRow(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> pkColumnNames,
			Consumer<byte[]> consumer
	) {
		Objects.requireNonNull(consumer, "consumer");
		return forEachPrimaryKeyRowUntil(
				property2Index,
				compositeIndexes,
				pkColumnNames,
				rowKey -> {
					consumer.accept(rowKey);
					return true;
				});
	}

	/**
	 * Stream PK row keys with early-stop when {@code visitor} returns {@code false}.
	 *
	 * @return {@code true} if PK tree found; {@code false} if missing
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static boolean forEachPrimaryKeyRowUntil(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> pkColumnNames,
			PrimaryKeyRowVisitor visitor
	) {
		Objects.requireNonNull(visitor, "visitor");
		final AbstractIndexOperation<?, ?> tree =
				resolvePrimaryKeyIndex(property2Index, compositeIndexes, pkColumnNames);
		if (tree == null) {
			return false;
		}
		if (tree instanceof AbstractBPTree bpTree) {
			bpTree.forEachLeafEntryUntil((indexKey, rowKey) -> visitor.visit(rowKey));
			return true;
		}
		// Sealed fallback / non-BPTree PK: searchAll then walk pointers (no leaf cursor).
		final IndexOperationResult result = tree.searchAll();
		if (result == null || result == IndexOperationResult.EMPTY) {
			return true;
		}
		result.expandBitmapPointers();
		if (result.getPointers() == null) {
			return true;
		}
		for (IndexPointerRef ptr : result.getPointers()) {
			final byte[] rowKey = ptr.resolveKey();
			if (rowKey != null && !visitor.visit(rowKey)) {
				return true;
			}
		}
		return true;
	}


	/**
	 * Open a resumable PK leaf cursor (Portal pull / FETCH). Caller must close the cursor.
	 *
	 * @return cursor, or {@code null} when PK tree missing
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static AbstractBPTree.RowKeyCursor<?, ?> openPrimaryKeyRowCursor(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> pkColumnNames
	) {
		final AbstractIndexOperation<?, ?> tree =
				resolvePrimaryKeyIndex(property2Index, compositeIndexes, pkColumnNames);
		if (tree == null) {
			return null;
		}
		if (!(tree instanceof AbstractBPTree bpTree)) {
			return null;
		}
		return bpTree.openRowKeyCursor();
	}

	/**
	 * Continues leaf walk while {@link #visit(byte[])} returns {@code true}.
	 */
	@FunctionalInterface
	public interface PrimaryKeyRowVisitor {
		boolean visit(byte[] rowKey);
	}

	/**
	 * Same contract as {@code GridCompositeIndex.resolveSingleColumnIndex}.
	 */
	static AbstractIndexOperation<byte[], SingleTreeKey> resolveSingleColumnIndex(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			String columnName
	) {
		if (columnName == null || columnName.isBlank() || property2Index == null) {
			return null;
		}
		final AbstractIndexOperation<byte[], SingleTreeKey> exact = property2Index.get(columnName);
		if (exact != null) {
			return exact;
		}
		for (Entry<String, AbstractIndexOperation<byte[], SingleTreeKey>> entry : property2Index.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(columnName)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Same contract as {@code GridCompositeIndex.resolveCompositeIndex}.
	 */
	static AbstractIndexOperation<byte[][], CompositeTreeKey> resolveCompositeIndex(
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> columns
	) {
		if (columns == null || columns.isEmpty() || compositeIndexes == null) {
			return null;
		}
		final AbstractIndexOperation<byte[][], CompositeTreeKey> exact = compositeIndexes.get(columns);
		if (exact != null) {
			return exact;
		}
		for (Entry<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> entry
				: compositeIndexes.entrySet()) {
			if (columnsEqualIgnoreCaseOrdered(entry.getKey(), columns)) {
				return entry.getValue();
			}
		}
		return null;
	}


	/** {@code true} when PK tree is an in-RAM {@link AbstractBPTree} (not sealed fallback). */
	public static boolean hasRamPrimaryKeyBptree(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> pkColumnNames
	) {
		final AbstractIndexOperation<?, ?> tree =
				resolvePrimaryKeyIndex(property2Index, compositeIndexes, pkColumnNames);
		return tree instanceof AbstractBPTree;
	}

	private static boolean columnsEqualIgnoreCaseOrdered(List<String> left, List<String> right) {
		if (left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			if (!left.get(i).equalsIgnoreCase(right.get(i))) {
				return false;
			}
		}
		return true;
	}
}
