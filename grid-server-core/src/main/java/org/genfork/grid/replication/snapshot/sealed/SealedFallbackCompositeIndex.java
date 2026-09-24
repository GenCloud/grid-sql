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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.IndexOperationResult;

/**
 * RAM multi-column BPTree with sealed shard readers as a cold-key fallback.
 * <p>
 * Full-key EQ/range encode via {@link SealedCompositeIndexKey}. Partial / left-prefix EQ
 * and composite LIKE / open or partial ranges merge sealed {@code .sbpt} via leaf-walk
 * (length-first order is not a contiguous prefix range). Ordered full-key EQ/BETWEEN seeks
 * stay on {@link SealedBPTreeReader#searchEq}/{@link SealedBPTreeReader#searchRange}.
 * {@link #searchAll} / {@link #searchNotEq} merge RAM with sealed leaf walk (no FULL
 * {@code .sbpt} hydrate into RAM BPTree).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedFallbackCompositeIndex
		extends AbstractSealedBpTreeFallback<byte[][], CompositeTreeKey> {
	private static final int LEFT_PREFIX_COLUMN = 0;
	private static final int NO_LIMIT = 0;

	public SealedFallbackCompositeIndex(AbstractIndexOperation<byte[][], CompositeTreeKey> delegate) {
		super(delegate);
	}

	@Override
	public IndexOperationResult searchEq(CompositeTreeKey key) {
		return searchEq(key, NO_LIMIT);
	}

	@Override
	public IndexOperationResult searchEq(CompositeTreeKey key, int maxResults) {
		final IndexOperationResult ram = maxResults > 0
				? delegate().searchEq(key, maxResults)
				: delegate().searchEq(key);
		if (key == null || !fullKey(key)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		if (maxResults > 0 && ram != null && ram.getPointers() != null
				&& ram.getPointers().size() >= maxResults) {
			return ram;
		}
		if (readersEmpty()) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		if (maxResults <= 0 && skipSealedOnRamHit(ram)) {
			return ram;
		}
		if (key.isPartial()) {
			return mergeRamAndSealed(ram, sealedPrefix(key), maxResults);
		}
		return mergeRamAndSealed(ram, sealedEq(key), maxResults);
	}

	@Override
	public IndexOperationResult searchRange(CompositeTreeKey low, CompositeTreeKey high) {
		final IndexOperationResult ram = delegate().searchRange(low, high);
		if (low == null || high == null) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		if (readersEmpty() || skipSealedOnRamHit(ram)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		if (low.isPartial() || high.isPartial() || !fullKey(low) || !fullKey(high)) {
			return mergeRamAndSealed(ram, sealedPartialRange(low, high), NO_LIMIT);
		}
		final byte[] lowEncoded = SealedCompositeIndexKey.encode(low.getKey());
		final byte[] highEncoded = SealedCompositeIndexKey.encode(high.getKey());
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchRange(lowEncoded, highEncoded));
		}
		return mergeRamAndSealed(ram, sealed, NO_LIMIT);
	}

	private List<byte[]> sealedPartialRange(CompositeTreeKey low, CompositeTreeKey high) {
		final int column = Math.max(low.partialColumnIndex(), high.partialColumnIndex());
		final byte[] lowBound = low.getKey().length > 0 ? low.getKey()[0] : null;
		final byte[] highBound = high.getKey().length > 0 ? high.getKey()[0] : null;
		final int col = column < 0 ? LEFT_PREFIX_COLUMN : column;
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchMatching(
					indexKey -> SealedCompositeIndexKey.matchesComponentRange(
							indexKey, col, lowBound, highBound)));
		}
		return sealed;
	}

	private List<byte[]> sealedEq(CompositeTreeKey key) {
		final byte[] encoded = SealedCompositeIndexKey.encode(key.getKey());
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchEq(encoded));
		}
		return sealed;
	}

	/**
	 * Partial EQ: left-prefix (first N components) or mid-column single-field match.
	 */
	private List<byte[]> sealedPrefix(CompositeTreeKey key) {
		final byte[][] parts = key.getKey();
		final int column = key.partialColumnIndex();
		final List<byte[]> sealed = new ArrayList<>();
		if (parts.length > 1 || column <= LEFT_PREFIX_COLUMN) {
			final byte[][] prefix = parts;
			for (SealedBPTreeReader reader : readers()) {
				sealed.addAll(reader.searchMatching(
						indexKey -> SealedCompositeIndexKey.matchesLeftPrefix(indexKey, prefix)));
			}
			return sealed;
		}
		final byte[] expected = parts[0];
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchMatching(
					indexKey -> SealedCompositeIndexKey.matchesComponent(indexKey, column, expected)));
		}
		return sealed;
	}

	private static boolean fullKey(CompositeTreeKey key) {
		final byte[][] parts = key.getKey();
		return parts != null && parts.length > 0;
	}

	@Override
	public IndexOperationResult searchNotEq(CompositeTreeKey key) {
		final IndexOperationResult ram = delegate().searchNotEq(key);
		if (key == null || !fullKey(key) || key.isPartial()
				|| readersEmpty() || skipSealedOnRamHit(ram)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		return mergeRamAndSealed(ram, sealedNotEq(key), NO_LIMIT);
	}

	private List<byte[]> sealedNotEq(CompositeTreeKey key) {
		final byte[] encoded = SealedCompositeIndexKey.encode(key.getKey());
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchMatching(indexKey -> !Arrays.equals(indexKey, encoded)));
		}
		return sealed;
	}

	/**
	 * Composite LIKE: sealed leaf-walk with wire {@link org.genfork.grid.serial.WireLikeMatcher}
	 * on encoded components (mirrors RAM {@code GridPointerCompositeBPTree#matchesPattern}).
	 */
	@Override
	public IndexOperationResult searchLike(CompositeTreeKey pattern) {
		final IndexOperationResult ram = delegate().searchLike(pattern);
		if (pattern == null) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		if (readersEmpty() || skipSealedOnRamHit(ram)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		return mergeRamAndSealed(ram, sealedLike(pattern), NO_LIMIT);
	}

	private List<byte[]> sealedLike(CompositeTreeKey pattern) {
		final byte[][] parts = pattern.getKey();
		final List<byte[]> sealed = new ArrayList<>();
		if (parts == null || parts.length == 0) {
			return sealed;
		}
		final int column = pattern.partialColumnIndex();
		if (column > LEFT_PREFIX_COLUMN && parts.length == 1) {
			final byte[] patternWire = parts[0];
			for (SealedBPTreeReader reader : readers()) {
				sealed.addAll(reader.searchMatching(
						indexKey -> SealedCompositeIndexKey.matchesComponentLike(
								indexKey, column, patternWire)));
			}
			return sealed;
		}
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchMatching(
					indexKey -> SealedCompositeIndexKey.matchesLikePatterns(indexKey, parts)));
		}
		return sealed;
	}

	@Override
	public IndexOperationResult searchGreaterThan(CompositeTreeKey key) {
		return mergeOpenBound(delegate().searchGreaterThan(key), key, OpenBound.GT);
	}

	@Override
	public IndexOperationResult searchGreaterThanOrEqual(CompositeTreeKey key) {
		return mergeOpenBound(delegate().searchGreaterThanOrEqual(key), key, OpenBound.GE);
	}

	@Override
	public IndexOperationResult searchLessThan(CompositeTreeKey key) {
		return mergeOpenBound(delegate().searchLessThan(key), key, OpenBound.LT);
	}

	@Override
	public IndexOperationResult searchLessThanOrEqual(CompositeTreeKey key) {
		return mergeOpenBound(delegate().searchLessThanOrEqual(key), key, OpenBound.LE);
	}

	private IndexOperationResult mergeOpenBound(
			IndexOperationResult ram,
			CompositeTreeKey key,
			OpenBound bound
	) {
		if (key == null || !fullKey(key) || key.isPartial()) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		if (readersEmpty() || skipSealedOnRamHit(ram)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		final byte[] encodedBound = SealedCompositeIndexKey.encode(key.getKey());
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchMatching(indexKey -> openCmp(indexKey, encodedBound, bound)));
		}
		return mergeRamAndSealed(ram, sealed, NO_LIMIT);
	}

	@Override
	public IndexOperationResult searchAll() {
		final IndexOperationResult ram = delegate().searchAll();
		if (readersEmpty() || skipSealedOnRamHit(ram)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		return mergeRamAndSealed(ram, sealedAll(), NO_LIMIT);
	}

	private List<byte[]> sealedAll() {
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchAll());
		}
		return sealed;
	}
}
