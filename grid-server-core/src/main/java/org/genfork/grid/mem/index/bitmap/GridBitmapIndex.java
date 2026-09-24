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
package org.genfork.grid.mem.index.bitmap;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Bitmap index for low-cardinality columns (opt-in via {@code IndexType.BITMAP}).
 * <p>
 * Supports sealed serialize/deserialize for durable {@code *.sbm} hydrate.
 *
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class GridBitmapIndex extends AbstractIndexOperation<byte[], SingleTreeKey> {
	private final String indexName;
	private final int bitmapSize;
	private final Map<SingleTreeKey, Bitmap> valueBitmaps;
	private final Map<SingleTreeKey, List<Integer>> keysToPosition;
	private final Map<Integer, IndexPointerRef> positionToPointer;

	public GridBitmapIndex(String indexName, int bitmapSize) {
		this.indexName = indexName;
		this.bitmapSize = bitmapSize;
		this.valueBitmaps = new ConcurrentHashMap<>();
		this.keysToPosition = new ConcurrentHashMap<>();
		this.positionToPointer = new ConcurrentHashMap<>();
	}

	public int getBitmapSize() {
		return bitmapSize;
	}

	/** Distinct index keys currently posted (fan-out denominator). */
	public long approxDistinctKeys() {
		return valueBitmaps.size();
	}

	/** Total postings / row pointers (fan-out numerator proxy). */
	public long approxPostings() {
		return positionToPointer.size();
	}

	/**
	 * IN-list via bitmap OR of EQ postings (same position space).
	 */
	public IndexOperationResult searchIn(Iterable<SingleTreeKey> keys) {
		if (keys == null) {
			return new IndexOperationResult(Collections.emptySet());
		}
		Bitmap combined = null;
		long processed = 0L;
		for (SingleTreeKey key : keys) {
			if (key == null) {
				continue;
			}
			final IndexOperationResult one = searchEq(key);
			processed += one.getRowsProcessed();
			if (!one.hasBitmap()) {
				continue;
			}
			if (combined == null) {
				combined = one.getBitmap().clone();
			} else {
				combined = combined.or(one.getBitmap());
			}
		}
		if (combined == null) {
			final IndexOperationResult empty = new IndexOperationResult(Collections.emptySet());
			empty.addProcessed(processed);
			return empty;
		}
		final IndexOperationResult operationResult = new IndexOperationResult();
		operationResult.setBitmap(combined);
		operationResult.setBitmapPositions(positionToPointer);
		operationResult.setSize(combined.getSetBitCount());
		operationResult.addProcessed(processed);
		return operationResult;
	}

	@Override
	public String getIndexName() {
		return indexName;
	}

	@Override
	public SingleTreeKey createKey(String indexedProperty, byte[] value) {
		return new SingleTreeKey(value);
	}

	@Override
	public SingleTreeKey createKey(byte[][] value) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void insert(SingleTreeKey key, IndexPointerRef pointerRef) {
		if (key == null || pointerRef == null) {
			return;
		}

		final Bitmap bitmap = valueBitmaps.computeIfAbsent(key, _ -> new Bitmap(bitmapSize));

		final int position = findFreePosition();
		if (position == -1) {
			throw new IllegalStateException("Bitmap index is full");
		}

		bitmap.setBit(position);

		keysToPosition.computeIfAbsent(key, _ -> new ArrayList<>()).add(position);
		positionToPointer.put(position, pointerRef);
	}

	@Override
	public void delete(SingleTreeKey key) {
		if (key == null) {
			return;
		}

		final Bitmap bitmap = valueBitmaps.get(key);
		if (bitmap == null) {
			return;
		}

		final List<Integer> positions = keysToPosition.get(key);
		for (Integer position : positions) {
			bitmap.clearBit(position);

			final IndexPointerRef pointer = positionToPointer.remove(position);
			if (pointer != null) {
				pointer.free();
			}
		}

		keysToPosition.remove(key);
		if (bitmap.isEmpty()) {
			valueBitmaps.remove(key);
		}
	}

	@Override
	public void deleteMatchingRowKey(SingleTreeKey key, byte[] rowKey) {
		if (key == null || rowKey == null) {
			return;
		}
		final Bitmap bitmap = valueBitmaps.get(key);
		final List<Integer> positions = keysToPosition.get(key);
		if (bitmap == null || positions == null || positions.isEmpty()) {
			return;
		}
		final Iterator<Integer> iterator = positions.iterator();
		while (iterator.hasNext()) {
			final Integer position = iterator.next();
			final IndexPointerRef pointer = positionToPointer.get(position);
			if (pointer == null) {
				iterator.remove();
				continue;
			}
			if (Arrays.equals(pointer.resolveKey(), rowKey)) {
				bitmap.clearBit(position);
				positionToPointer.remove(position);
				pointer.free();
				iterator.remove();
			}
		}
		if (positions.isEmpty()) {
			keysToPosition.remove(key);
		}
		if (bitmap.isEmpty()) {
			valueBitmaps.remove(key);
		}
	}

	@Override
	public void deletePointer(SingleTreeKey key, IndexPointerRef pointerRef) {
		if (pointerRef == null) {
			delete(key);
			return;
		}
		deleteMatchingRowKey(key, pointerRef.resolveKey());
	}

	@Override
	public void clear() {
		valueBitmaps.clear();

		for (SingleTreeKey key : keysToPosition.keySet()) {
			final List<Integer> positions = keysToPosition.remove(key);
			if (positions != null) {
				positions.clear();
			}
		}

		for (IndexPointerRef pointerRef : positionToPointer.values()) {
			pointerRef.free();
		}

		positionToPointer.clear();
	}

	/**
	 * Visit each posting as {@code (indexKey, rowKey)} for sealed dump / shard filter.
	 */
	public void forEachEntry(BiConsumer<byte[], byte[]> consumer) {
		if (consumer == null) {
			return;
		}
		for (Map.Entry<SingleTreeKey, Bitmap> entry : valueBitmaps.entrySet()) {
			final byte[] indexKey = entry.getKey().getKey();
			entry.getValue().forEachSetBit(position -> {
				final IndexPointerRef pointer = positionToPointer.get(position);
				if (pointer == null) {
					return;
				}
				final byte[] rowKey = pointer.resolveKey();
				if (rowKey != null) {
					consumer.accept(indexKey, rowKey);
				}
			});
		}
	}

	/**
	 * Opaque sealed payload: {@code bitmapSize + postingCount + (indexKey, rowKey)*}.
	 */
	public byte[] serialize() {
		final List<byte[][]> postings = new ArrayList<>();
		forEachEntry((indexKey, rowKey) -> postings.add(new byte[][]{indexKey, rowKey}));
		int bytes = Integer.BYTES * 2;
		for (byte[][] posting : postings) {
			bytes += Short.BYTES + posting[0].length + Short.BYTES + posting[1].length;
		}
		final ByteBuffer buffer = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
		buffer.putInt(bitmapSize).putInt(postings.size());
		for (byte[][] posting : postings) {
			putBytes(buffer, posting[0]);
			putBytes(buffer, posting[1]);
		}
		return buffer.array();
	}

	/**
	 * Rebuild a bitmap index from {@link #serialize()} payload (row keys as cached pointer refs).
	 */
	public static GridBitmapIndex deserialize(String indexName, byte[] payload) {
		if (payload == null || payload.length < Integer.BYTES * 2) {
			throw new IllegalArgumentException("bitmap payload too short");
		}
		final ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
		final int size = buffer.getInt();
		if (size <= 0) {
			throw new IllegalArgumentException("invalid bitmap size: " + size);
		}
		final int count = buffer.getInt();
		if (count < 0) {
			throw new IllegalArgumentException("negative posting count: " + count);
		}
		final GridBitmapIndex index = new GridBitmapIndex(indexName, size);
		for (int i = 0; i < count; i++) {
			final byte[] indexKey = getBytes(buffer);
			final byte[] rowKey = getBytes(buffer);
			index.insert(new SingleTreeKey(indexKey), new IndexPointerRef(0L, rowKey));
		}
		if (buffer.hasRemaining()) {
			throw new IllegalArgumentException("trailing bytes in bitmap payload: " + buffer.remaining());
		}
		return index;
	}

	/**
	 * Replace contents with postings from another serialized/hydrated source (same bitmapSize).
	 */
	public void loadFrom(GridBitmapIndex source) {
		if (source == null) {
			clear();
			return;
		}
		if (source.bitmapSize != bitmapSize) {
			throw new IllegalArgumentException("bitmap size mismatch: " + source.bitmapSize + " vs " + bitmapSize);
		}
		clear();
		source.forEachEntry((indexKey, rowKey) ->
				insert(new SingleTreeKey(indexKey), new IndexPointerRef(0L, rowKey)));
	}

	@Override
	public IndexOperationResult searchEq(SingleTreeKey key) {
		if (key == null) {
			return new IndexOperationResult(Collections.emptySet());
		}

		final Bitmap bitmap = valueBitmaps.get(key);
		if (bitmap == null) {
			return new IndexOperationResult(Collections.emptySet());
		}

		final IndexOperationResult operationResult = new IndexOperationResult();
		operationResult.setBitmap(bitmap.clone());
		operationResult.setBitmapPositions(positionToPointer);
		operationResult.setSize(bitmap.getSetBitCount());
		operationResult.incProcessed();
		// Defer HashSet expansion — AND/OR may combine BitSets first.
		return operationResult;
	}

	@Override
	public IndexOperationResult searchNotEq(SingleTreeKey key) {
		final Bitmap bitmap = valueBitmaps.get(key);
		if (bitmap == null) {
			return new IndexOperationResult(new HashSet<>(positionToPointer.values()));
		}

		final IndexOperationResult operationResult = new IndexOperationResult();
		final Bitmap resultBitmap = bitmap.not();
		operationResult.setBitmap(resultBitmap);
		operationResult.setBitmapPositions(positionToPointer);
		operationResult.setSize(resultBitmap.getSetBitCount());
		operationResult.incProcessed();
		return operationResult;
	}

	@Override
	public IndexOperationResult searchLike(SingleTreeKey pattern) {
		return null;
	}

	@Override
	public IndexOperationResult searchGreaterThan(SingleTreeKey key) {
		final IndexOperationResult operationResult = new IndexOperationResult();

		final Set<IndexPointerRef> result = new HashSet<>();

		for (Map.Entry<SingleTreeKey, Bitmap> entry : valueBitmaps.entrySet()) {
			final SingleTreeKey subKey = entry.getKey();
			if (subKey.compareFull(key) > 0) {
				final Bitmap bitmap = entry.getValue();
				bitmap.forEachSetBit(position -> {
					final IndexPointerRef pointer = positionToPointer.get(position);
					if (pointer != null) {
						result.add(pointer);
						operationResult.incSize();
					}
				});
			}

			operationResult.incProcessed();
		}

		operationResult.setPointers(result);
		return operationResult;
	}

	@Override
	public IndexOperationResult searchGreaterThanOrEqual(SingleTreeKey key) {
		final IndexOperationResult operationResult = new IndexOperationResult();

		final Set<IndexPointerRef> result = new HashSet<>();

		for (Map.Entry<SingleTreeKey, Bitmap> entry : valueBitmaps.entrySet()) {
			final SingleTreeKey subKey = entry.getKey();
			if (subKey.compareFull(key) >= 0) {
				final Bitmap bitmap = entry.getValue();
				bitmap.forEachSetBit(position -> {
					final IndexPointerRef pointer = positionToPointer.get(position);
					if (pointer != null) {
						result.add(pointer);
						operationResult.incSize();
					}
				});
			}

			operationResult.incProcessed();
		}

		operationResult.setPointers(result);
		return operationResult;
	}

	@Override
	public IndexOperationResult searchLessThan(SingleTreeKey key) {
		final IndexOperationResult operationResult = new IndexOperationResult();

		final Set<IndexPointerRef> result = new HashSet<>();

		for (Map.Entry<SingleTreeKey, Bitmap> entry : valueBitmaps.entrySet()) {
			final SingleTreeKey subKey = entry.getKey();
			if (subKey.compareFull(key) < 0) {
				final Bitmap bitmap = entry.getValue();
				bitmap.forEachSetBit(position -> {
					final IndexPointerRef pointer = positionToPointer.get(position);
					if (pointer != null) {
						result.add(pointer);
						operationResult.incSize();
					}
				});
			}

			operationResult.incProcessed();
		}

		operationResult.setPointers(result);
		return operationResult;
	}

	@Override
	public IndexOperationResult searchLessThanOrEqual(SingleTreeKey key) {
		final IndexOperationResult operationResult = new IndexOperationResult();

		final Set<IndexPointerRef> result = new HashSet<>();

		for (Map.Entry<SingleTreeKey, Bitmap> entry : valueBitmaps.entrySet()) {
			final SingleTreeKey subKey = entry.getKey();
			if (subKey.compareFull(key) <= 0) {
				final Bitmap bitmap = entry.getValue();
				bitmap.forEachSetBit(position -> {
					final IndexPointerRef pointer = positionToPointer.get(position);
					if (pointer != null) {
						result.add(pointer);
						operationResult.incSize();
					}
				});
			}

			operationResult.incProcessed();
		}

		operationResult.setPointers(result);
		return operationResult;
	}

	@Override
	public IndexOperationResult searchRange(SingleTreeKey low, SingleTreeKey high) {
		if (low == null || high == null) {
			return new IndexOperationResult(Collections.emptySet());
		}

		final IndexOperationResult operationResult = new IndexOperationResult();

		final Set<IndexPointerRef> result = new HashSet<>();

		for (Map.Entry<SingleTreeKey, Bitmap> entry : valueBitmaps.entrySet()) {
			final SingleTreeKey key = entry.getKey();
			if (isKeyInRange(key, low, high)) {
				final Bitmap bitmap = entry.getValue();
				bitmap.forEachSetBit(position -> {
					final IndexPointerRef pointer = positionToPointer.get(position);
					if (pointer != null) {
						result.add(pointer);
						operationResult.incSize();
					}
				});
			}

			operationResult.incProcessed();
		}

		operationResult.setPointers(result);
		return operationResult;
	}

	@Override
	public IndexOperationResult searchAll() {
		final Collection<IndexPointerRef> values = positionToPointer.values();
		return new IndexOperationResult(new HashSet<>(values));
	}

	private int findFreePosition() {
		for (int i = 0; i < bitmapSize; i++) {
			if (!positionToPointer.containsKey(i)) {
				return i;
			}
		}
		return -1;
	}

	private boolean isKeyInRange(SingleTreeKey key, SingleTreeKey startKey, SingleTreeKey endKey) {
		return key.compareFull(startKey) >= 0 && key.compareFull(endKey) <= 0;
	}

	private static void putBytes(ByteBuffer buffer, byte[] bytes) {
		if (bytes.length > 0xffff) {
			throw new IllegalArgumentException("bitmap key exceeds unsigned-short limit");
		}
		buffer.putShort((short) bytes.length).put(bytes);
	}

	private static byte[] getBytes(ByteBuffer buffer) {
		if (buffer.remaining() < Short.BYTES) {
			throw new IllegalArgumentException("truncated bitmap key length");
		}
		final int length = Short.toUnsignedInt(buffer.getShort());
		if (length > buffer.remaining()) {
			throw new IllegalArgumentException("truncated bitmap key bytes");
		}
		final byte[] bytes = new byte[length];
		buffer.get(bytes);
		return bytes;
	}
}
