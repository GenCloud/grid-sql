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
package org.genfork.grid.query.plan.strategy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.ArrayDataType;
import org.genfork.grid.mem.index.ArrayIndexType;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.BPValueHelper;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.query.plan.PagingData;
import org.genfork.grid.query.plan.SortOrderData;
import org.genfork.grid.query.plan.SortOrderData.OrderDirection;
import org.genfork.grid.query.storage.TmpFileManager;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.WireFieldCompare;
import org.genfork.grid.serial.WireSpan;

/**
 * Filter then ORDER BY: external-order {@link ArrayIndexType} when present, else wire-row compare.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class FilterThenSortStrategy implements QueryScanStrategy {
	/** Prefer Top-K heap when OFFSET+LIMIT is at most this many rows. */
	private static final int TOP_K_MAX_NEED = 1024;
	/** Above this candidate count, spill to external merge sort. */
	private static final long EXTERNAL_SORT_MIN_SIZE = 1_000_000L;
	/** Rows per external-sort chunk file. */
	private static final int EXTERNAL_SORT_CHUNK_SIZE = 50_000;

	private final Map<String, ArrayIndexType> cachedOrderIndexFields;
	private final TableSchema schema;
	private final Function<byte[], byte[]> rowResolver;

	public FilterThenSortStrategy(Map<String, ArrayIndexType> cachedOrderIndexFields) {
		this(cachedOrderIndexFields, null, null);
	}

	public FilterThenSortStrategy(
			Map<String, ArrayIndexType> cachedOrderIndexFields,
			TableSchema schema,
			Function<byte[], byte[]> rowResolver
	) {
		this.cachedOrderIndexFields = cachedOrderIndexFields;
		this.schema = schema;
		this.rowResolver = rowResolver;
	}

	@Override
	public List<byte[]> execute(ExplainQuery.QueryPlan queryPlan,
	                            IndexOperationResult operationResult,
	                            SortOrderData[] sortOrderData,
	                            PagingData pagingData) {
		final Set<IndexPointerRef> filteredPointers = operationResult.getPointers();
		final long resultSize = filteredPointers == null ? 0L : filteredPointers.size();
		operationResult.setSize(resultSize);

		final int pageSize = pagingData == null ? -1 : pagingData.limit();
		final int pageOffset = pagingData == null ? 0 : Math.max(0, pagingData.offset());
		if (pagingData != null && pageSize == 0) {
			return List.of();
		}

		// No external-order accelerator: sort by wire field bytes from row blobs (SPI-adjacent).
		if (!canUseExternalOrderCompare(sortOrderData)) {
			return wireRowSort(queryPlan, filteredPointers, sortOrderData, pageOffset, pageSize);
		}

		final int topNeed = pageSize > 0 ? pageOffset + pageSize : -1;
		if (topNeed > 0 && topNeed <= TOP_K_MAX_NEED && resultSize > topNeed) {
			return topKSort(queryPlan, filteredPointers, sortOrderData, pagingData, topNeed);
		}

		if (resultSize < EXTERNAL_SORT_MIN_SIZE) {
			return quickSort(queryPlan, filteredPointers, sortOrderData, pagingData);
		}

		return externalSort(queryPlan, filteredPointers, sortOrderData, pagingData);
	}

	private boolean canUseExternalOrderCompare(SortOrderData[] sortOrderData) {
		if (sortOrderData == null || sortOrderData.length == 0 || cachedOrderIndexFields == null
				|| cachedOrderIndexFields.isEmpty()) {
			return false;
		}
		for (SortOrderData data : sortOrderData) {
			if (data == null || data.sortField() == null
					|| !cachedOrderIndexFields.containsKey(data.sortField())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Decorate-sort ORDER BY: O(n) blob+cursor once, then O(n log n) span compares (no alloc).
	 */
	private List<byte[]> wireRowSort(
			ExplainQuery.QueryPlan queryPlan,
			Set<IndexPointerRef> filteredPointers,
			SortOrderData[] orderData,
			int pageOffset,
			int pageSize
	) {
		ExplainQuery.QueryPlanNode indexNode = null;
		if (queryPlan != null) {
			indexNode = ExplainQuery.startNode(queryPlan, "Wire Row Sort",
					"decorate ORDER BY for " + Arrays.toString(orderData));
		}
		if (filteredPointers == null || filteredPointers.isEmpty()) {
			if (indexNode != null) {
				ExplainQuery.endNode(indexNode, 0, 0);
				queryPlan.completeCurrentNode();
			}
			return List.of();
		}

		final int[] ordinals = resolveSortOrdinals(orderData);
		final boolean[] descending = resolveSortDescending(orderData);
		final WireSortRow[] rows = new WireSortRow[filteredPointers.size()];
		int n = 0;
		for (IndexPointerRef ptr : filteredPointers) {
			if (ptr == null || ptr.isFree()) {
				continue;
			}
			rows[n++] = decorateWireSortRow(ptr, ordinals);
		}
		final WireSortRow[] toSort = n == rows.length ? rows : Arrays.copyOf(rows, n);
		Arrays.sort(toSort, new WireSortRowComparator(descending));

		final int limit = pageSize > 0 ? pageSize : Integer.MAX_VALUE;
		final List<byte[]> result = new ArrayList<>(IndexPointerRef.listCapacity(limit));
		int skipped = 0;
		for (WireSortRow row : toSort) {
			if (skipped < pageOffset) {
				skipped++;
				continue;
			}
			if (result.size() >= limit) {
				break;
			}
			final byte[] key = row.ptr().resolveKey();
			if (key != null) {
				result.add(key);
			}
		}
		if (indexNode != null) {
			ExplainQuery.endNode(indexNode, n, result.size());
			queryPlan.completeCurrentNode();
		}
		return result;
	}

	private int[] resolveSortOrdinals(SortOrderData[] orderData) {
		if (orderData == null || orderData.length == 0) {
			return new int[0];
		}
		final int[] ordinals = new int[orderData.length];
		for (int i = 0; i < orderData.length; i++) {
			ordinals[i] = -1;
			if (orderData[i] == null || orderData[i].sortField() == null || schema == null) {
				continue;
			}
			final ColumnDef col = schema.column(orderData[i].sortField());
			if (col != null) {
				ordinals[i] = col.ordinal();
			}
		}
		return ordinals;
	}

	private static boolean[] resolveSortDescending(SortOrderData[] orderData) {
		if (orderData == null || orderData.length == 0) {
			return new boolean[0];
		}
		final boolean[] descending = new boolean[orderData.length];
		for (int i = 0; i < orderData.length; i++) {
			descending[i] = orderData[i] != null
					&& orderData[i].direction() == OrderDirection.DESC;
		}
		return descending;
	}

	private WireSortRow decorateWireSortRow(IndexPointerRef ptr, int[] ordinals) {
		final WireSpan[] keys = new WireSpan[ordinals.length];
		if (schema == null || rowResolver == null || ordinals.length == 0) {
			Arrays.fill(keys, WireSpan.nullSpan());
			return new WireSortRow(ptr, keys);
		}
		final byte[] rowKey = ptr.resolveKey();
		final byte[] blob = rowKey == null ? null : rowResolver.apply(rowKey);
		if (blob == null) {
			Arrays.fill(keys, WireSpan.nullSpan());
			return new WireSortRow(ptr, keys);
		}
		final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, blob);
		for (int i = 0; i < ordinals.length; i++) {
			if (ordinals[i] < 0) {
				keys[i] = WireSpan.nullSpan();
			} else {
				final WireSpan span = cursor.indexKeySpan(ordinals[i]);
				keys[i] = span == null ? WireSpan.nullSpan() : span;
			}
		}
		return new WireSortRow(ptr, keys);
	}

	/**
	 * Bounded heap for ORDER BY + small LIMIT — avoids full {@code Long[]} sort.
	 */
	private List<byte[]> topKSort(ExplainQuery.QueryPlan queryPlan,
	                              Set<IndexPointerRef> filteredPointers,
	                              SortOrderData[] orderData,
	                              PagingData pagingData,
	                              int topNeed) {
		ExplainQuery.QueryPlanNode indexNode = null;
		if (queryPlan != null) {
			indexNode = ExplainQuery.startNode(queryPlan, "Top-K Sort",
					"Top-" + topNeed + " heap sort for " + Arrays.toString(orderData));
		}

		final CompositeIndexValuesComparator comparator = new CompositeIndexValuesComparator(orderData, cachedOrderIndexFields);
		// Max-heap of worst among current top-K (reversed sort order).
		final PriorityQueue<Long> worstOfBest = new PriorityQueue<>(topNeed + 1, comparator.reversed());
		long rowsProcessed = 0;
		for (IndexPointerRef ptr : filteredPointers) {
			if (ptr == null || ptr.isFree()) {
				continue;
			}
			rowsProcessed++;
			final long pointer = ptr.getPointer();
			if (worstOfBest.size() < topNeed) {
				worstOfBest.offer(pointer);
			} else if (comparator.compare(pointer, worstOfBest.peek()) < 0) {
				worstOfBest.poll();
				worstOfBest.offer(pointer);
			}
		}

		final Long[] ranked = worstOfBest.toArray(new Long[0]);
		Arrays.sort(ranked, comparator);

		final int pageSize = pagingData.limit();
		final int pageOffset = Math.max(0, pagingData.offset());
		final List<byte[]> result = new ArrayList<>(IndexPointerRef.listCapacity(pageSize));
		for (int i = pageOffset; i < ranked.length && result.size() < pageSize; i++) {
			final byte[] key = BPValueHelper.keyFromNativeRef(ranked[i]);
			if (key != null) {
				result.add(key);
			}
		}

		if (indexNode != null) {
			ExplainQuery.endNode(indexNode, rowsProcessed, result.size());
			queryPlan.completeCurrentNode();
		}
		return result;
	}

	private List<byte[]> quickSort(ExplainQuery.QueryPlan queryPlan, Set<IndexPointerRef> filteredPointers, SortOrderData[] orderData, PagingData pagingData) {
		ExplainQuery.QueryPlanNode indexNode = null;
		if (queryPlan != null) {
			indexNode = ExplainQuery.startNode(queryPlan, "Quick Sort", "In-memory quick sort for " + Arrays.toString(orderData));
		}

		final Long[] pointersArray = new Long[filteredPointers.size()];
		int n = 0;
		for (IndexPointerRef ptr : filteredPointers) {
			pointersArray[n++] = ptr.getPointer();
		}
		final Long[] toSort = n == pointersArray.length ? pointersArray : Arrays.copyOf(pointersArray, n);

		final CompositeIndexValuesComparator comparator = new CompositeIndexValuesComparator(orderData, cachedOrderIndexFields);
		Arrays.sort(toSort, comparator);

		final int pageSize = pagingData.limit();
		final int pageOffset = pagingData.offset();

		final List<byte[]> result = new ArrayList<>(IndexPointerRef.listCapacity(pageSize));

		long limit = pageSize;
		long toSkip = Math.max(0, pageOffset);

		long rowsProcessed = 0;

		for (long pointer : toSort) {
			rowsProcessed++;

			if (toSkip > 0) {
				toSkip--;
				continue;
			}

			if (limit-- == 0) {
				break;
			}

			final byte[] key = BPValueHelper.keyFromNativeRef(pointer);
			if (key != null) {
				result.add(key);
			}
		}

		if (indexNode != null) {
			ExplainQuery.endNode(indexNode, rowsProcessed, result.size());
			queryPlan.completeCurrentNode();
		}

		return result;
	}

	private List<byte[]> externalSort(ExplainQuery.QueryPlan queryPlan, Set<IndexPointerRef> filteredPointers, SortOrderData[] orderData, PagingData pagingData) {
		long startMemory = 0;
		ExplainQuery.QueryPlanNode indexNode = null;
		if (queryPlan != null) {
			indexNode = ExplainQuery.startNode(queryPlan, "External Sort", "External merge sort for " + Arrays.toString(orderData));

			startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		}

		try {
			final List<Path> chunkFiles = new ArrayList<>();

			final int chunkSize = EXTERNAL_SORT_CHUNK_SIZE;

			int currentChunk = 0;

			final CompositeIndexValuesComparator comparator = new CompositeIndexValuesComparator(orderData, cachedOrderIndexFields);

			final List<Long> chunk = new ArrayList<>(chunkSize);

			for (IndexPointerRef ptr : filteredPointers) {
				if (ptr.isFree()) {
					continue;
				}

				chunk.add(ptr.getPointer());

				if (chunk.size() >= chunkSize) {
					ExplainQuery.QueryPlanNode chunkNode = null;
					if (queryPlan != null) {
						chunkNode = ExplainQuery.startNode(queryPlan, "Sort Chunk", "Sorting chunk " + currentChunk);
					}

					chunk.sort(comparator);

					chunkFiles.add(saveChunk(chunk, currentChunk++));

					if (chunkNode != null) {
						ExplainQuery.endNode(chunkNode, chunkSize, chunkSize);
					}

					chunk.clear();
				}
			}

			long totalRowsProcessed = 0;

			if (!chunk.isEmpty()) {
				ExplainQuery.QueryPlanNode chunkNode = null;
				if (queryPlan != null) {
					chunkNode = ExplainQuery.startNode(queryPlan, "Sort Chunk", "Sorting chunk " + currentChunk);
				}

				chunk.sort(comparator);
				chunkFiles.add(saveChunk(chunk, currentChunk));

				totalRowsProcessed += chunk.size();

				if (chunkNode != null) {
					ExplainQuery.endNode(chunkNode, chunk.size(), chunk.size());
				}
			}

			ExplainQuery.QueryPlanNode mergeNode = null;
			if (queryPlan != null) {
				mergeNode = ExplainQuery.startNode(queryPlan, "Merge Chunks", "Merging " + chunkFiles.size() + " sorted chunks");
			}

			final int limit = pagingData.limit();
			final int offset = pagingData.offset();

			final List<byte[]> result = new ArrayList<>(IndexPointerRef.listCapacity(limit));
			mergeChunks(comparator, result, chunkFiles, limit, offset);

			if (mergeNode != null) {
				ExplainQuery.endNode(mergeNode, totalRowsProcessed, limit);
			}

			for (Path file : chunkFiles) {
				TmpFileManager.getInstance().addTmpForDeletionPath(file);
			}

			if (indexNode != null) {
				final long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

				ExplainQuery.recordMemoryUsage(indexNode, endMemory - startMemory);
				ExplainQuery.endNode(indexNode, totalRowsProcessed, limit);

				queryPlan.completeCurrentNode();
			}

			return result;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private Path saveChunk(List<Long> chunk, int chunkIndex) throws IOException {
		final Path tempFile = Files.createTempFile("chunk_" + chunkIndex + "_", ".dat");
		try (DataOutputStream dos = new DataOutputStream(
				new BufferedOutputStream(Files.newOutputStream(tempFile)))) {
			for (Long l : chunk) {
				dos.writeLong(l);
			}
		}
		return tempFile;
	}

	private void mergeChunks(CompositeIndexValuesComparator sorter,
	                         List<byte[]> result, List<Path> chunkFiles,
	                         int pageSize, int pageOffset) throws IOException {
		final ChunkReader[] readers = new ChunkReader[chunkFiles.size()];
		final long[] currentValues = new long[readers.length];

		for (int i = 0; i < chunkFiles.size(); i++) {
			readers[i] = new ChunkReader(chunkFiles.get(i));
			currentValues[i] = readers[i].readNext();
		}

		try {
			final PriorityQueue<ChunkItem> heap = new PriorityQueue<>(
					chunkFiles.size(),
					(a, b) -> sorter.compare(currentValues[a.chunkIndex], currentValues[b.chunkIndex])
			);

			for (int i = 0; i < currentValues.length; i++) {
				if (currentValues[i] != -1) {
					heap.offer(new ChunkItem(currentValues[i], i));
				}
			}

			int skipped = 0;
			while (skipped < pageOffset && !heap.isEmpty()) {
				final ChunkItem item = heap.poll();
				final int chunkIndex = item.chunkIndex;

				currentValues[chunkIndex] = readers[chunkIndex].readNext();
				if (currentValues[chunkIndex] != -1) {
					heap.offer(new ChunkItem(currentValues[chunkIndex], chunkIndex));
				}
				skipped++;
			}

			int count = 0;
			while (count < pageSize && !heap.isEmpty()) {
				final ChunkItem item = heap.poll();
				final int chunkIndex = item.chunkIndex;

				final byte[] key = BPValueHelper.keyFromNativeRef(item.value);
				if (key != null) {
					result.add(key);
					count++;
				}

				currentValues[chunkIndex] = readers[chunkIndex].readNext();
				if (currentValues[chunkIndex] != -1) {
					heap.offer(new ChunkItem(currentValues[chunkIndex], chunkIndex));
				}
			}
		} finally {
			for (ChunkReader reader : readers) {
				reader.close();
			}
		}
	}

	static class ChunkReader {
		private final DataInputStream dis;
		private boolean hasNext = true;

		public ChunkReader(Path file) throws IOException {
			dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));
		}

		public long readNext() {
			if (!hasNext) {
				return -1;
			}

			try {
				if (dis.available() > 0) {
					return dis.readLong();
				}

				hasNext = false;
				return -1;
			} catch (IOException e) {
				hasNext = false;
				return -1;
			}
		}

		public void close() throws IOException {
			dis.close();
		}
	}

	static class ChunkItem {
		long value;
		int chunkIndex;

		ChunkItem(long value, int chunkIndex) {
			this.value = value;
			this.chunkIndex = chunkIndex;
		}
	}

	/**
	 * Decorate entry: PK pointer + ORDER BY wire spans rooted in the row blob for sort lifetime.
	 */
	private record WireSortRow(IndexPointerRef ptr, WireSpan[] keys) {
	}

	private static final class WireSortRowComparator implements Comparator<WireSortRow> {
		private final boolean[] descending;

		WireSortRowComparator(boolean[] descending) {
			this.descending = descending == null ? new boolean[0] : descending;
		}

		@Override
		public int compare(WireSortRow left, WireSortRow right) {
			if (left == right) {
				return 0;
			}
			if (left == null) {
				return -1;
			}
			if (right == null) {
				return 1;
			}
			final WireSpan[] leftKeys = left.keys();
			final WireSpan[] rightKeys = right.keys();
			final int cols = Math.min(leftKeys.length, rightKeys.length);
			for (int i = 0; i < cols; i++) {
				final int cmp = WireFieldCompare.compare(leftKeys[i], rightKeys[i]);
				if (cmp != 0) {
					return i < descending.length && descending[i] ? -cmp : cmp;
				}
			}
			return Long.compare(left.ptr().getPointer(), right.ptr().getPointer());
		}
	}

	private static class CompositeIndexValuesComparator implements Comparator<Long> {
		private ChainDelayedComparator chainComparator;

		public CompositeIndexValuesComparator(SortOrderData[] orderData, Map<String, ArrayIndexType> cachedOrderIndexFields) {
			for (SortOrderData data : orderData) {
				final OrderDirection direction = data.direction();
				final ArrayIndexType tuple = cachedOrderIndexFields == null
						? null
						: cachedOrderIndexFields.get(data.sortField());
				if (tuple == null) {
					continue;
				}

				if (chainComparator == null) {
					chainComparator = ChainDelayedComparator.ofNext(new DefaultByteArrayComparator(tuple, direction == OrderDirection.DESC));
				} else {
					chainComparator = ChainDelayedComparator.ofAll(
							chainComparator.next(),
							new DefaultByteArrayComparator(tuple, direction == OrderDirection.DESC)
					);
				}
			}
		}

		@Override
		public int compare(
				Long o1,
				Long o2
		) {
			if (Objects.equals(o1, o2)) {
				return 0;
			}

			if (o1 == null) {
				return -1;
			}

			if (o2 == null) {
				return 1;
			}

			if (chainComparator == null) {
				return Long.compare(o1, o2);
			}

			return chainComparator.compare(o1, o2);
		}
	}

	static class DefaultByteArrayComparator {
		private final ArrayIndexType arrayIndexType;
		private final boolean reversed;

		DefaultByteArrayComparator(ArrayIndexType arrayIndexType, boolean reversed) {
			this.arrayIndexType = arrayIndexType;
			this.reversed = reversed;
		}

		public int compare(long ptr1, long ptr2) {
			final int index = arrayIndexType.getIndex();
			final ArrayDataType.Cmp comparator = arrayIndexType.getComparator();
			return reversed ? comparator.compare(ptr2, ptr1, index) : comparator.compare(ptr1, ptr2, index);
		}
	}

	// Avoid runtime lambda linkage so compare stays a direct call.
	record ChainDelayedComparator(DefaultByteArrayComparator prev, DefaultByteArrayComparator next,
	                              boolean isNext) {
		public static ChainDelayedComparator ofNext(DefaultByteArrayComparator next) {
			return new ChainDelayedComparator(null, next, true);
		}

		public static ChainDelayedComparator ofAll(DefaultByteArrayComparator prev, DefaultByteArrayComparator next) {
			return new ChainDelayedComparator(prev, next, false);
		}

		public int compare(long ptr1, long ptr2) {
			if (isNext) {
				return next.compare(ptr1, ptr2);
			}

			final int res = prev.compare(ptr1, ptr2);
			if (res != 0) {
				return res;
			}

			if (next == null) {
				return 0;
			}

			return next.compare(ptr1, ptr2);
		}
	}
}
