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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

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

/**
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

	public FilterThenSortStrategy(Map<String, ArrayIndexType> cachedOrderIndexFields) {
		this.cachedOrderIndexFields = cachedOrderIndexFields;
	}

	@Override
	public List<byte[]> execute(ExplainQuery.QueryPlan queryPlan,
	                            IndexOperationResult operationResult,
	                            SortOrderData[] sortOrderData,
	                            PagingData pagingData) {
		final Set<IndexPointerRef> filteredPointers = operationResult.getPointers();
		final long resultSize = filteredPointers == null ? 0L : filteredPointers.size();
		operationResult.setSize(resultSize);

		final int pageSize = pagingData.limit();
		final int pageOffset = Math.max(0, pagingData.offset());
		final int topNeed = pageSize > 0 ? pageOffset + pageSize : -1;
		if (topNeed > 0 && topNeed <= TOP_K_MAX_NEED && resultSize > topNeed) {
			return topKSort(queryPlan, filteredPointers, sortOrderData, pagingData, topNeed);
		}

		if (resultSize < EXTERNAL_SORT_MIN_SIZE) {
			return quickSort(queryPlan, filteredPointers, sortOrderData, pagingData);
		}

		return externalSort(queryPlan, filteredPointers, sortOrderData, pagingData);
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

	private static class CompositeIndexValuesComparator implements Comparator<Long> {
		private ChainDelayedComparator chainComparator;

		public CompositeIndexValuesComparator(SortOrderData[] orderData, Map<String, ArrayIndexType> cachedOrderIndexFields) {
			for (SortOrderData data : orderData) {
				final OrderDirection direction = data.direction();
				final ArrayIndexType tuple = cachedOrderIndexFields == null
						? null
						: cachedOrderIndexFields.get(data.sortField());
				if (tuple == null) {
					// Sort field is not an external-order column — skip FilterThenSort key extract.
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
