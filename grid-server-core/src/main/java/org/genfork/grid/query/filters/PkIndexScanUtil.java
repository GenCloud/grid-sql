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
import java.util.Objects;
import java.util.function.Consumer;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexColumnResolveUtil;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.AbstractBPTree;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.replication.snapshot.sealed.AbstractSealedBpTreeFallback;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackCompositeIndex;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackIndex;

/**
 * Shared PRIMARY KEY tree resolve + scan for filter / maintenance paths.
 * <p>
 * Exact bind: single PK column → scalar map entry; multi → composite whose
 * columns match {@code pkColumnNames} order-insensitively (same contract as
 * {@link IndexColumnResolveUtil}).
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
			return IndexColumnResolveUtil.resolveSingleColumnIndex(property2Index, pkColumnNames.getFirst());
		}
		return IndexColumnResolveUtil.resolveCompositeIndex(compositeIndexes, pkColumnNames);
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
	 * @return cursor, or {@code null} when PK tree missing or portal-ineligible (sealed-bound)
	 */
	public static AbstractBPTree.RowKeyCursor<?, ?> openPrimaryKeyRowCursor(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> pkColumnNames
	) {
		final AbstractIndexOperation<?, ?> tree =
				resolvePrimaryKeyIndex(property2Index, compositeIndexes, pkColumnNames);
		final AbstractBPTree<?, ?> ram = portalRamBpTree(tree);
		if (ram == null) {
			return null;
		}
		return ram.openRowKeyCursor();
	}

	/**
	 * RAM {@link AbstractBPTree} usable for Portal PK leaf cursor, or {@code null}
	 * when missing / sealed-bound ({@link AbstractSealedBpTreeFallback#hasSealedReaders()}).
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static AbstractBPTree<?, ?> portalRamBpTree(AbstractIndexOperation<?, ?> tree) {
		if (tree == null) {
			return null;
		}
		if (tree instanceof AbstractBPTree<?, ?> bpTree) {
			return bpTree;
		}
		if (tree instanceof SealedFallbackIndex || tree instanceof SealedFallbackCompositeIndex) {
			final AbstractSealedBpTreeFallback<?, ?> sealed = (AbstractSealedBpTreeFallback<?, ?>) tree;
			if (sealed.hasSealedReaders()) {
				return null;
			}
			final AbstractIndexOperation<?, ?> delegate = sealed.delegate();
			return delegate instanceof AbstractBPTree<?, ?> bpTree ? bpTree : null;
		}
		return null;
	}

	/**
	 * Continues leaf walk while {@link #visit(byte[])} returns {@code true}.
	 */
	@FunctionalInterface
	public interface PrimaryKeyRowVisitor {
		boolean visit(byte[] rowKey);
	}

	/**
	 * {@code true} when PK resolves to a portal-eligible in-RAM {@link AbstractBPTree}
	 * (plain tree, or sealed wrapper with no readers yet).
	 */
	public static boolean hasRamPrimaryKeyBptree(
			Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index,
			Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes,
			List<String> pkColumnNames
	) {
		final AbstractIndexOperation<?, ?> tree =
				resolvePrimaryKeyIndex(property2Index, compositeIndexes, pkColumnNames);
		return portalRamBpTree(tree) != null;
	}

}
