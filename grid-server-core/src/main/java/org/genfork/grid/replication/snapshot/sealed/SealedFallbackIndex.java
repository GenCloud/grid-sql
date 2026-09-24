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
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.WireLikeMatcher;

/**
 * RAM single-column index with sealed shard readers as a cold-key fallback.
 * <p>
 * EQ/range merge sealed seeks. {@link #searchLike} merges a sealed leaf-walk using
 * {@link WireLikeMatcher} (same {@code %}/{@code _} semantics as RAM). Trailing-percent
 * patterns use a starts-with fast path inside the matcher; length-first sealed key order
 * prevents a contiguous range seek for {@code LIKE 'foo%'}.
 * {@link #searchAll} / {@link #searchNotEq} merge RAM with sealed leaf walk (no FULL
 * {@code .sbpt} hydrate into RAM BPTree).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedFallbackIndex extends AbstractSealedBpTreeFallback<byte[], SingleTreeKey> {
	private static final int NO_LIMIT = 0;

	public SealedFallbackIndex(AbstractIndexOperation<byte[], SingleTreeKey> delegate) {
		super(delegate);
	}

	@Override
	public IndexOperationResult searchEq(SingleTreeKey key) {
		return searchEq(key, NO_LIMIT);
	}

	@Override
	public IndexOperationResult searchEq(SingleTreeKey key, int maxResults) {
		final IndexOperationResult ram = maxResults > 0
				? delegate().searchEq(key, maxResults)
				: delegate().searchEq(key);
		// LIMIT already satisfied from RAM working-set — skip sealed walk.
		if (maxResults > 0 && ram != null && ram.getPointers() != null
				&& ram.getPointers().size() >= maxResults) {
			return ram;
		}
		if (readersEmpty()) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		// FULL working-set hydrate: RAM hit implies bucket complete (eviction drops index ptrs with map keys).
		// Skip sealed only when RAM already found rows and no LIMIT forced a partial probe above.
		if (maxResults <= 0 && skipSealedOnRamHit(ram)) {
			return ram;
		}
		return mergeRamAndSealed(ram, sealedEq(key), maxResults);
	}

	@Override
	public IndexOperationResult searchRange(SingleTreeKey low, SingleTreeKey high) {
		final IndexOperationResult ram = delegate().searchRange(low, high);
		if (readersEmpty() || skipSealedOnRamHit(ram)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchRange(low.getKey(), high.getKey()));
		}
		return mergeRamAndSealed(ram, sealed, NO_LIMIT);
	}

	private List<byte[]> sealedEq(SingleTreeKey key) {
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchEq(key.getKey()));
		}
		return sealed;
	}

	private List<byte[]> sealedLike(SingleTreeKey pattern) {
		final byte[] patternWire = pattern.getKey();
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchMatching(
					indexKey -> WireLikeMatcher.matchesWire(indexKey, patternWire)));
		}
		return sealed;
	}

	@Override
	public IndexOperationResult searchNotEq(SingleTreeKey key) {
		final IndexOperationResult ram = delegate().searchNotEq(key);
		if (key == null || readersEmpty() || skipSealedOnRamHit(ram)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		return mergeRamAndSealed(ram, sealedNotEq(key), NO_LIMIT);
	}

	private List<byte[]> sealedNotEq(SingleTreeKey key) {
		final byte[] exclude = key.getKey();
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchMatching(indexKey -> !Arrays.equals(indexKey, exclude)));
		}
		return sealed;
	}

	@Override
	public IndexOperationResult searchLike(SingleTreeKey pattern) {
		final IndexOperationResult ram = delegate().searchLike(pattern);
		if (pattern == null) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		if (readersEmpty() || skipSealedOnRamHit(ram)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		return mergeRamAndSealed(ram, sealedLike(pattern), NO_LIMIT);
	}

	@Override
	public IndexOperationResult searchGreaterThan(SingleTreeKey key) {
		return mergeOpenBound(delegate().searchGreaterThan(key), key, OpenBound.GT);
	}

	@Override
	public IndexOperationResult searchGreaterThanOrEqual(SingleTreeKey key) {
		return mergeOpenBound(delegate().searchGreaterThanOrEqual(key), key, OpenBound.GE);
	}

	@Override
	public IndexOperationResult searchLessThan(SingleTreeKey key) {
		return mergeOpenBound(delegate().searchLessThan(key), key, OpenBound.LT);
	}

	@Override
	public IndexOperationResult searchLessThanOrEqual(SingleTreeKey key) {
		return mergeOpenBound(delegate().searchLessThanOrEqual(key), key, OpenBound.LE);
	}

	private IndexOperationResult mergeOpenBound(IndexOperationResult ram, SingleTreeKey key, OpenBound bound) {
		if (key == null) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		if (readersEmpty() || skipSealedOnRamHit(ram)) {
			return ram == null ? IndexOperationResult.EMPTY : ram;
		}
		final byte[] boundKey = key.getKey();
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedBPTreeReader reader : readers()) {
			sealed.addAll(reader.searchMatching(indexKey -> openCmp(indexKey, boundKey, bound)));
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
