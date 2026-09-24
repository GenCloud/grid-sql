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

import org.genfork.grid.mem.ByteArrayContainer;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RAM BITMAP index with sealed {@code .sbm} shards as a cold-key fallback (merge, not full replace).
 * <p>
 * Mirrors {@link SealedFallbackIndex} for {@code .sbpt}: EQ/IN merge RAM + sealed postings;
 * FULL hydrate may mark RAM authoritative to skip sealed when the working set already hit.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedFallbackBitmap extends AbstractSealedFallbackIndex<byte[], SingleTreeKey> {
	private final CopyOnWriteArrayList<SealedShard> sealedShards = new CopyOnWriteArrayList<>();

	public SealedFallbackBitmap(GridBitmapIndex delegate) {
		super(delegate);
	}

	@Override
	public GridBitmapIndex delegate() {
		return (GridBitmapIndex) super.delegate();
	}

	/**
	 * Attach or replace sealed hydrate for one shard (same property).
	 */
	public void addSealed(int shard, GridBitmapIndex sealed) {
		Objects.requireNonNull(sealed, "sealed");
		sealedShards.removeIf(existing -> existing.shard() == shard);
		sealedShards.add(new SealedShard(shard, sealed));
	}

	/** {@code true} when {@code op} is a BITMAP working-set or sealed fallback wrapper. */
	public static boolean isBitmapIndex(AbstractIndexOperation<?, ?> op) {
		return op instanceof GridBitmapIndex || op instanceof SealedFallbackBitmap;
	}

	/** Unwrap sealed wrapper to the RAM {@link GridBitmapIndex}, or cast when already bare. */
	public static GridBitmapIndex unwrap(AbstractIndexOperation<?, ?> op) {
		if (op instanceof SealedFallbackBitmap fallback) {
			return fallback.delegate();
		}
		if (op instanceof GridBitmapIndex bitmap) {
			return bitmap;
		}
		return null;
	}

	@Override
	public IndexOperationResult searchEq(SingleTreeKey key) {
		return searchEq(key, 0);
	}

	@Override
	public IndexOperationResult searchEq(SingleTreeKey key, int maxResults) {
		final IndexOperationResult ram = maxResults > 0
				? delegate().searchEq(key, maxResults)
				: delegate().searchEq(key);
		if (maxResults > 0 && ram != null && pointerCount(ram) >= maxResults) {
			return expand(ram);
		}
		if (sealedShards.isEmpty()) {
			return expand(ram);
		}
		if (maxResults <= 0 && ramAuthoritative() && pointerCount(ram) > 0) {
			return expand(ram);
		}
		return mergeRamAndSealed(ram, sealedEq(key), maxResults);
	}

	/**
	 * IN-list: OR of EQ postings across RAM + sealed (same merge as {@link #searchEq}).
	 */
	public IndexOperationResult searchIn(Iterable<SingleTreeKey> keys) {
		if (keys == null) {
			return IndexOperationResult.EMPTY;
		}
		final Map<ByteArrayContainer, IndexPointerRef> byKey = new LinkedHashMap<>();
		long processed = 0L;
		for (SingleTreeKey key : keys) {
			if (key == null) {
				continue;
			}
			final IndexOperationResult one = searchEq(key);
			processed += one.getRowsProcessed();
			for (IndexPointerRef pointer : one.pointersOrExpand()) {
				final byte[] rowKey = pointer.resolveKey();
				if (rowKey != null) {
					byKey.putIfAbsent(new ByteArrayContainer(rowKey), pointer);
				}
			}
		}
		final IndexOperationResult result = new IndexOperationResult();
		result.setPointers(new LinkedHashSet<>(byKey.values()));
		result.setSize(byKey.size());
		result.addProcessed(processed);
		return result;
	}

	@Override
	public IndexOperationResult searchNotEq(SingleTreeKey key) {
		return expand(delegate().searchNotEq(key));
	}

	@Override
	public IndexOperationResult searchLike(SingleTreeKey pattern) {
		return expand(delegate().searchLike(pattern));
	}

	@Override
	public IndexOperationResult searchGreaterThan(SingleTreeKey key) {
		return expand(delegate().searchGreaterThan(key));
	}

	@Override
	public IndexOperationResult searchGreaterThanOrEqual(SingleTreeKey key) {
		return expand(delegate().searchGreaterThanOrEqual(key));
	}

	@Override
	public IndexOperationResult searchLessThan(SingleTreeKey key) {
		return expand(delegate().searchLessThan(key));
	}

	@Override
	public IndexOperationResult searchLessThanOrEqual(SingleTreeKey key) {
		return expand(delegate().searchLessThanOrEqual(key));
	}

	@Override
	public IndexOperationResult searchRange(SingleTreeKey low, SingleTreeKey high) {
		return expand(delegate().searchRange(low, high));
	}

	@Override
	public IndexOperationResult searchAll() {
		return expand(delegate().searchAll());
	}

	private List<byte[]> sealedEq(SingleTreeKey key) {
		final List<byte[]> sealed = new ArrayList<>();
		for (SealedShard shard : sealedShards) {
			final IndexOperationResult hit = shard.index().searchEq(key);
			for (IndexPointerRef pointer : hit.pointersOrExpand()) {
				final byte[] rowKey = pointer.resolveKey();
				if (rowKey != null) {
					sealed.add(rowKey);
				}
			}
		}
		return sealed;
	}

	private static IndexOperationResult expand(IndexOperationResult result) {
		if (result == null) {
			return IndexOperationResult.EMPTY;
		}
		result.expandBitmapPointers();
		return result;
	}

	private static int pointerCount(IndexOperationResult result) {
		if (result == null) {
			return 0;
		}
		result.expandBitmapPointers();
		return result.getPointers() == null ? 0 : result.getPointers().size();
	}

	private record SealedShard(int shard, GridBitmapIndex index) {
	}
}
