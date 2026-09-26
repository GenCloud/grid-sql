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
package org.genfork.grid.mem.index.btree;

import one.nio.util.Cleaner;
import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.health.SimpleIndexHealthAnalyzer;
import org.genfork.grid.mem.index.health.SimpleIndexHealthAnalyzer.IndexHealthMetrics;
import org.genfork.grid.query.plan.ExplainQuery;
import org.springframework.lang.NonNull;
import org.springframework.util.CollectionUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;
import java.util.stream.Stream;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public abstract class AbstractBPTree<K, T extends TreeKey<K>> extends AbstractIndexOperation<K, T> {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AbstractBPTree.class);
	private static final int DEFAULT_ORDER = 36;
	private final boolean strict;


	/**
	 * Accumulates leaf pointer sets into an ArrayList (ordered, limited-friendly).
	 */
	protected static final class PointerSetAccumulator {
		/** Default capacity when unbounded ({@code maxSize == 0}). */
		private static final int ACCUMULATOR_DEFAULT_CAPACITY = 16;
		/**
		 * Cap on ArrayList constructor capacity. QueryParser uses a large sentinel LIMIT
		 * for unbounded SELECT; pre-sizing to that sentinel OOMs before any leaf walk.
		 */
		private static final int ACCUMULATOR_MAX_INITIAL_CAPACITY = 4096;

		private final ArrayList<IndexPointerRef> list;
		private final int maxSize;

		PointerSetAccumulator() {
			this(0);
		}

		PointerSetAccumulator(int maxSize) {
			this.maxSize = Math.max(0, maxSize);
			final int initial = this.maxSize > 0
					? Math.min(this.maxSize, ACCUMULATOR_MAX_INITIAL_CAPACITY)
					: ACCUMULATOR_DEFAULT_CAPACITY;
			this.list = new ArrayList<>(initial);
		}

		/**
		 * @return false when maxSize reached (caller should stop walking)
		 */
		boolean addAll(Set<IndexPointerRef> currentPointers) {
			if (currentPointers == null || currentPointers.isEmpty()) {
				return !isFull();
			}
			for (IndexPointerRef p : currentPointers) {
				list.add(p);
				if (maxSize > 0 && list.size() >= maxSize) {
					return false;
				}
			}
			return !isFull();
		}

		boolean isFull() {
			return maxSize > 0 && list.size() >= maxSize;
		}

		Set<IndexPointerRef> get() {
			if (list.isEmpty()) {
				return Collections.emptySet();
			}
			final ArrayList<IndexPointerRef> snap = list;
			return new java.util.AbstractSet<>() {
				@Override
				public Iterator<IndexPointerRef> iterator() {
					return snap.iterator();
				}
				@Override
				public int size() {
					return snap.size();
				}
				@Override
				public boolean contains(Object o) {
					return snap.contains(o);
				}
			};
		}
	}

	private final String indexName;
	private final IndexStat indexStat;
	private Node<K, T> root;

	public AbstractBPTree(String indexName, boolean strict) {
		this.indexName = indexName;
		this.strict = strict;
		indexStat = new IndexStat();
		root = createLeafNode();
		new Cleaner(this) {
			@Override
			public void clear() {
				AbstractBPTree.this.clear();
			}
		};
	}

	protected abstract int getKeyMemorySize(T key);

	@NonNull
	protected abstract InternalNode<K, T> createInternalNode();

	@NonNull
	protected abstract LeafNode<K, T> createLeafNode();

	protected abstract boolean matchesPattern(T bytes, int from, T pattern, int to);

	@Override
	public void insert(T key, IndexPointerRef pointerRef) {
		final LeafSplit<K, T> split = root.insert(key, pointerRef, DEFAULT_ORDER, strict);
		if (split != null) {
			final InternalNode<K, T> newRoot = createInternalNode();
			newRoot.keys.add(split.getSplitKey());
			newRoot.children.add(root);
			newRoot.children.add(split.getRight());
			root = newRoot;
		}
	}

	@Override
	public void delete(T key) {
		root.delete(key, DEFAULT_ORDER);
		shrinkRootIfNeeded();
	}

	/**
	 * Drop only the posting(s) whose {@link IndexPointerRef} resolves to {@code rowKey}.
	 * Leaves other LAX pointers under the same index value intact.
	 */
	@Override
	public void deleteMatchingRowKey(T key, byte[] rowKey) {
		if (key == null || rowKey == null) {
			return;
		}
		root.deleteMatchingRowKey(key, rowKey, DEFAULT_ORDER);
		shrinkRootIfNeeded();
	}

	/**
	 * Remove one concrete pointer under {@code key} (writer LAX/STRICT upsert).
	 */
	@Override
	public void deletePointer(T key, IndexPointerRef pointerRef) {
		if (key == null || pointerRef == null) {
			return;
		}
		root.deletePointer(key, pointerRef, DEFAULT_ORDER);
		shrinkRootIfNeeded();
	}

	private void shrinkRootIfNeeded() {
		if (root instanceof InternalNode<K, T> internalNode && internalNode.children.size() == 1) {
			root = internalNode.children.getFirst();
		}
	}

	@Override
	public void clear() {
		final Node<K, T> oldNode = root;
		root = createLeafNode();
		oldNode.clear();
	}

	@Override
	public IndexOperationResult searchEq(T key) {
		if (key == null) {
			return new IndexOperationResult();
		}
		return root.searchEq(key);
	}

	@Override
	public IndexOperationResult searchEq(T key, int maxResults) {
		if (key == null || maxResults <= 0) {
			return searchEq(key);
		}
		return root.searchEqLimited(key, maxResults);
	}

	@Override
	public IndexOperationResult searchLike(T pattern) {
		final IndexOperationResult operationResult = new IndexOperationResult();
		final PointerSetAccumulator acc = new PointerSetAccumulator();
		LeafNode<K, T> current = getFirstLeafNode();
		while (current != null) {
			for (int i = 0; i < current.keys.size(); i++) {
				final T key = current.keys.get(i);
				if (matchesPattern(key, 0, pattern, 0)) {
					final Set<IndexPointerRef> currentPointers = current.values.get(i);
					acc.addAll(currentPointers);
					operationResult.addSize(currentPointers.size());
				}
				operationResult.incProcessed();
			}
			current = current.next;
		}
		operationResult.setPointers(acc.get());
		return operationResult;
	}

	@Override
	public IndexOperationResult searchGreaterThan(T key) {
		return root.searchGreaterThan(key);
	}

	@Override
	public IndexOperationResult searchGreaterThanOrEqual(T key) {
		return root.searchGreaterThanOrEqual(key);
	}

	@Override
	public IndexOperationResult searchLessThan(T key) {
		return root.searchLessThan(key);
	}

	@Override
	public IndexOperationResult searchLessThanOrEqual(T key) {
		return root.searchLessThanOrEqual(key);
	}

	@Override
	public IndexOperationResult searchRange(T low, T high) {
		return root.searchRange(low, high);
	}

	@Override
	public IndexOperationResult searchNotEq(T key) {
		return root.searchNotEqual(key);
	}

	@Override
	public IndexOperationResult searchAll() {
		return root.searchAll();
	}

	public void forEachLeafEntry(BiConsumer<K, byte[]> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		forEachLeafEntryUntil((indexKey, rowKey) -> {
			consumer.accept(indexKey, rowKey);
			return true;
		});
	}

	/**
	 * Walk leaf postings left-to-right; stop when {@code visitor} returns {@code false}.
	 */
	public void forEachLeafEntryUntil(LeafEntryVisitor<K> visitor) {
		Objects.requireNonNull(visitor, "visitor");
		LeafNode<K, T> current = getFirstLeafNode();
		while (current != null) {
			for (int i = 0; i < current.keys.size(); i++) {
				final K indexKey = current.keys.get(i).getKey();
				for (IndexPointerRef pointer : current.values.get(i)) {
					final byte[] rowKey = pointer.resolveKey();
					if (rowKey != null && !visitor.visit(indexKey, rowKey)) {
						return;
					}
				}
			}
			current = current.next;
		}
	}

	/**
	 * Continues the leaf walk while {@link #visit} returns {@code true}.
	 *
	 * @param <K> index key type
	 */
	@FunctionalInterface
	public interface LeafEntryVisitor<K> {
		boolean visit(K indexKey, byte[] rowKey);
	}

	/**
	 * Resumable left-to-right walk of row PK bytes (Portal pull / FETCH windows).
	 */
	public RowKeyCursor<K, T> openRowKeyCursor() {
		return new RowKeyCursor<>(getFirstLeafNode());
	}

	/**
	 * Stateful leaf posting cursor — no HashSet / full key List.
	 *
	 * @param <K> index key type
	 * @param <T> tree key type
	 */
	public static final class RowKeyCursor<K, T extends TreeKey<K>> {
		private LeafNode<K, T> leaf;
		private int keyIndex;
		private Iterator<IndexPointerRef> pointerIter;
		private byte[] nextRowKey;
		private boolean closed;

		private RowKeyCursor(LeafNode<K, T> first) {
			this.leaf = first;
			this.keyIndex = 0;
			advance();
		}

		public boolean hasNext() {
			return !closed && nextRowKey != null;
		}

		public byte[] next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			final byte[] out = nextRowKey;
			advance();
			return out;
		}

		public void close() {
			closed = true;
			nextRowKey = null;
			leaf = null;
			pointerIter = null;
		}

		private void advance() {
			nextRowKey = null;
			while (leaf != null) {
				if (pointerIter != null && pointerIter.hasNext()) {
					final byte[] key = pointerIter.next().resolveKey();
					if (key != null) {
						nextRowKey = key;
						return;
					}
					continue;
				}
				pointerIter = null;
				if (keyIndex >= leaf.keys.size()) {
					leaf = leaf.next;
					keyIndex = 0;
					continue;
				}
				pointerIter = leaf.values.get(keyIndex).iterator();
				keyIndex++;
			}
		}
	}

	public LeafNode<K, T> getFirstLeafNode() {
		Node<K, T> currentNode = root;
		while (!(currentNode instanceof LeafNode)) {
			final InternalNode<K, T> internal = (InternalNode<K, T>) currentNode;
			if (internal.children.isEmpty()) {
				return null;
			}
			currentNode = internal.children.getFirst();
		}
		return (LeafNode<K, T>) currentNode;
	}

	public LeafNode<K, T> getLastLeafNode() {
		Node<K, T> currentNode = root;
		while (!(currentNode instanceof LeafNode)) {
			currentNode = ((InternalNode<K, T>) currentNode).children.getLast();
		}
		return (LeafNode<K, T>) currentNode;
	}

	public void traversePagingOrder(ExplainQuery.QueryPlanNode indexNode, Set<IndexPointerRef> filteredPointers, Consumer<byte[]> valueConsumer, boolean sameFilteredIndex, int offset, int limit, boolean ascending) {
		LeafNode<K, T> startNode = ascending ? getFirstLeafNode() : getLastLeafNode();
		long toSkip;
		if (this instanceof GridPointerCompositeBPTree) {
			long remainingSkip = Math.max(0L, offset);
			while (startNode != null && remainingSkip > 0) {
				final long leafRecordCount = startNode.values.stream().mapToLong(Set::size).sum();
				if (leafRecordCount <= remainingSkip) {
					remainingSkip -= leafRecordCount;
					startNode = ascending ? startNode.next : startNode.prev;
					continue;
				}
				break;
			}
			toSkip = remainingSkip;
		} else {
			toSkip = Math.max(0L, offset);
		}
		int count = 0;
		while (startNode != null && count < limit) {
			long rowsProcessed = 0;
			int loops = 0;
			final int keysSize = startNode.keys.size();
			for (int i = 0; i < keysSize; i++) {
				final int keyIndex = ascending ? i : keysSize - 1 - i;
				final Set<IndexPointerRef> leafPointers = startNode.values.get(keyIndex);
				loops++;
				rowsProcessed += leafPointers.size();
				for (IndexPointerRef leafPointer : leafPointers) {
					if (!sameFilteredIndex) {
						if (!filteredPointers.contains(leafPointer)) {
							if (indexNode != null) {
								ExplainQuery.recordIndexUsage(indexNode, indexName, false);
							}
							continue;
						}
					}
					if (toSkip > 0) {
						if (indexNode != null) {
							ExplainQuery.recordIndexUsage(indexNode, indexName, false);
						}
						toSkip--;
						continue;
					}
					if (count < limit) {
						final byte[] key = leafPointer.toArray();
						if (key != null) {
							if (indexNode != null) {
								ExplainQuery.recordIndexUsage(indexNode, indexName, true);
							}
							valueConsumer.accept(key);
							count++;
						} else {
							if (indexNode != null) {
								ExplainQuery.recordIndexUsage(indexNode, indexName, false);
							}
						}
						continue;
					}
					break;
				}
				if (count >= limit) {
					break;
				}
			}
			if (indexNode != null) {
				indexNode.rowsProcessed = rowsProcessed;
				indexNode.loops = loops;
			}
			startNode = ascending ? startNode.next : startNode.prev;
		}
	}

	@Override
	public void processAnalyze() {
		indexStat.totalRecords = totalRecords();
		indexStat.cardinality = cardinality();
		collectStatsRecursive(root, 0);
		final IndexHealthMetrics healthMetrics = SimpleIndexHealthAnalyzer.calculateHealthMetrics(indexStat);
		if (healthMetrics.isRequiresOptimization()) {
			log.warn("Index [{}] require optimization. Priority {}, Status {}", indexName, healthMetrics.getOptimizationPriority(), healthMetrics.getHealthStatus());
		}
	}

	@Override
	public void dumpStats(String name) {
		try {
			final Path resolvePath = Paths.get(".", "log", "index-stats");
			final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
			final String currentDateString = "[" + name + "] " + LocalDateTime.now().format(formatter) + ".txt";
			final Path path = Files.createDirectories(resolvePath).resolve(currentDateString);
			final StringJoiner joiner = new StringJoiner(System.lineSeparator());
			joiner.add("=========================Short stats=========================");
			joiner.add(indexStat.toString());
			joiner.add("=========================Leaf stats=========================");
			collectTree(joiner);
			Files.write(path, joiner.toString().getBytes(), CREATE, TRUNCATE_EXISTING);
		} catch (Exception e) {
		}
		//
	}

	@SuppressWarnings("ForLoopReplaceableByForEach")
	public void collectTree(StringJoiner joiner) {
		if (root == null) {
			joiner.add("Index is empty");
			return;
		}
		final Map<Integer, List<Node<K, T>>> nodesByLevel = new HashMap<>();
		final Map<Integer, List<String>> statsByLevel = new HashMap<>();
		collectNodesByLevel(root, 0, nodesByLevel);
		for (Map.Entry<Integer, List<Node<K, T>>> entry : nodesByLevel.entrySet()) {
			final int level = entry.getKey();
			final List<String> statsList = new ArrayList<>();
			final List<Node<K, T>> value = entry.getValue();
			for (int i = 0; i < value.size(); i++) {
				final Node<K, T> node = value.get(i);
				final String nodeType;
				final long memory;
				if (node instanceof InternalNode<K, T> internalNode) {
					nodeType = "Internal";
					memory = calculateInternalNodeSize(internalNode);
				} else {
					nodeType = "Leaf";
					memory = calculateLeafNodeSize((LeafNode<K, T>) node);
				}
				final String stat = String.format("%s [keys: %d, memory: %d B (%.2f KiB, %.2f MiB)]", nodeType, node.keys.size(), memory, memory / 1024.0, memory / (1024.0 * 1024.0));
				statsList.add(stat);
			}
			statsByLevel.put(level, statsList);
		}
		printTreeRecursive(joiner, root, "", true, statsByLevel, 0);
	}

	@SuppressWarnings("ForLoopReplaceableByForEach")
	private void collectNodesByLevel(Node<K, T> node, int level, Map<Integer, List<Node<K, T>>> nodesByLevel) {
		if (node == null) {
			return;
		}
		nodesByLevel.computeIfAbsent(level, _ -> new ArrayList<>()).add(node);
		if (node instanceof InternalNode<K, T> internalNode) {
			final List<Node<K, T>> children = internalNode.children;
			for (int i = 0; i < children.size(); i++) {
				final Node<K, T> child = children.get(i);
				collectNodesByLevel(child, level + 1, nodesByLevel);
			}
		}
	}

	private void printTreeRecursive(StringJoiner joiner, Node<K, T> node, String prefix, boolean tail, Map<Integer, List<String>> statsByLevel, int level) {
		if (node == null) {
			return;
		}
		final List<String> levelStats = statsByLevel.get(level);
		if (CollectionUtils.isEmpty(levelStats)) {
			return;
		}
		final String nodeStat = levelStats.getFirst();
		levelStats.removeFirst();
		joiner.add(prefix + (tail ? "└── " : "├── ") + nodeStat);
		if (node instanceof InternalNode<K, T> internalNode) {
			final List<Node<K, T>> children = internalNode.children;
			for (int i = 0; i < children.size() - 1; i++) {
				printTreeRecursive(joiner, children.get(i), prefix + (tail ? "    " : "│   "), false, statsByLevel, level + 1);
			}
			if (!children.isEmpty()) {
				printTreeRecursive(joiner, children.getLast(), prefix + (tail ? "    " : "│   "), true, statsByLevel, level + 1);
			}
		}
	}

	public long cardinality() {
		long count = 0;
		LeafNode<K, T> current = getFirstLeafNode();
		while (current != null) {
			count += current.keys.size();
			current = current.next;
		}
		return count;
	}

	@SuppressWarnings("ForLoopReplaceableByForEach")
	private long totalRecords() {
		long count = 0;
		LeafNode<K, T> current = getFirstLeafNode();
		while (current != null) {
			final List<Set<IndexPointerRef>> values = current.values;
			for (int i = 0; i < values.size(); i++) {
				final Set<IndexPointerRef> valueSet = values.get(i);
				count += valueSet.size();
			}
			current = current.next;
		}
		return count;
	}

	@SuppressWarnings("ForLoopReplaceableByForEach")
	private void collectStatsRecursive(Node<K, T> node, int depth) {
		if (node == null) {
			return;
		}
		indexStat.nodeCount++;
		indexStat.totalKeys += node.keys.size();
		if (node instanceof InternalNode<K, T> internalNode) {
			indexStat.internalNodeCount++;
			final long nodeSize = calculateInternalNodeSize(internalNode);
			indexStat.totalMemory += nodeSize;
			indexStat.memoryByDepth.put(depth, indexStat.memoryByDepth.getOrDefault(depth, 0L) + nodeSize);
			final List<Node<K, T>> children = internalNode.children;
			for (int i = 0; i < children.size(); i++) {
				final Node<K, T> child = children.get(i);
				collectStatsRecursive(child, depth + 1);
			}
		} else if (node instanceof LeafNode<K, T> leafNode) {
			indexStat.leafNodeCount++;
			final long nodeSize = calculateLeafNodeSize(leafNode);
			indexStat.totalMemory += nodeSize;
			indexStat.memoryByDepth.put(depth, indexStat.memoryByDepth.getOrDefault(depth, 0L) + nodeSize);
		}
	}

	@SuppressWarnings("ForLoopReplaceableByForEach")
	private long calculateInternalNodeSize(InternalNode<K, T> node) {
		long size = 0;
		size += Long.BYTES * 2; // obj header size
		size += Long.BYTES; // keys/children pointers
		size += Long.BYTES * 6; // inner lists size (keys/children)
		// keys
		final List<T> keys = node.keys;
		for (int i = 0; i < keys.size(); i++) {
			final T key = keys.get(i);
			size += getKeyMemorySize(key); // obj header size
		}
		// children - only pointers
		size += ((long) Integer.BYTES) * node.children.size();
		return size;
	}

	@SuppressWarnings("ForLoopReplaceableByForEach")
	private long calculateLeafNodeSize(LeafNode<K, T> node) {
		long size = 0;
		size += Long.BYTES * 2; // obj header size
		size += Integer.BYTES * 3; // refs: keys, values, next
		size += Long.BYTES * 6; // inner lists size (keys/values)
		// keys
		final List<T> keys = node.keys;
		for (int i = 0; i < keys.size(); i++) {
			final T key = keys.get(i);
			size += getKeyMemorySize(key); // obj header size + key length
		}
		// values
		final List<Set<IndexPointerRef>> values = node.values;
		for (int i = 0; i < values.size(); i++) {
			final Set<IndexPointerRef> valueSet = values.get(i);
			size += Long.BYTES * 2; // obj header size
			size += (long) Long.BYTES * valueSet.size(); // obj header size + pointer length
		}
		return size;
	}


	public static abstract class Node<K, T extends TreeKey<K>> {
		public List<T> keys = new ArrayList<>();

		/**
		 * First index where {@code cmp.applyAsInt(keys.get(i)) <= 0}, or {@code keys.size()}.
		 */
		protected static <E> int lowerBoundLe(List<E> keys, java.util.function.ToIntFunction<E> cmp) {
			int low = 0;
			int high = keys.size() - 1;
			int ans = keys.size();
			while (low <= high) {
				final int mid = (low + high) >>> 1;
				if (cmp.applyAsInt(keys.get(mid)) <= 0) {
					ans = mid;
					high = mid - 1;
				} else {
					low = mid + 1;
				}
			}
			return ans;
		}

		/**
		 * First index where {@code cmp.applyAsInt(keys.get(i)) < 0}, or {@code keys.size()}.
		 */
		protected static <E> int lowerBoundLt(List<E> keys, java.util.function.ToIntFunction<E> cmp) {
			int low = 0;
			int high = keys.size() - 1;
			int ans = keys.size();
			while (low <= high) {
				final int mid = (low + high) >>> 1;
				if (cmp.applyAsInt(keys.get(mid)) < 0) {
					ans = mid;
					high = mid - 1;
				} else {
					low = mid + 1;
				}
			}
			return ans;
		}

		abstract LeafSplit<K, T> insert(T key, IndexPointerRef pointerRef, int order, boolean strict);

		abstract void delete(T key, int order);

		abstract void deleteMatchingRowKey(T key, byte[] rowKey, int order);

		abstract void deletePointer(T key, IndexPointerRef pointerRef, int order);

		abstract void clear();

		abstract IndexOperationResult searchEq(T key);

		IndexOperationResult searchEqLimited(T key, int maxResults) {
			return searchEq(key);
		}

		abstract IndexOperationResult searchGreaterThan(T key);

		abstract IndexOperationResult searchGreaterThanOrEqual(T key);

		abstract IndexOperationResult searchLessThan(T key);

		abstract IndexOperationResult searchLessThanOrEqual(T key);

		abstract IndexOperationResult searchRange(T low, T high);

		abstract IndexOperationResult searchNotEqual(T key);

		abstract IndexOperationResult searchAll();
	}


	public static abstract class InternalNode<K, T extends TreeKey<K>> extends Node<K, T> {
		List<Node<K, T>> children = new ArrayList<>();

		@NonNull
		protected abstract InternalNode<K, T> createInternalNode();

		@NonNull
		protected abstract LeafSplit<K, T> createLeafSplit(T splitKey, InternalNode<K, T> right);

		@Override
		public LeafSplit<K, T> insert(T key, IndexPointerRef pointerRef, int order, boolean strict) {
			final int idx = findIndex(key, true);
			final LeafSplit<K, T> split = children.get(idx).insert(key, pointerRef, order, strict);
			if (split != null) {
				keys.add(idx, split.getSplitKey());
				children.add(idx + 1, split.getRight());
				if (keys.size() >= order) {
					return split();
				}
			}
			return null;
		}

		@Override
		public void delete(T key, int order) {
			final int idx = findIndex(key, true);
			if (idx >= children.size()) {
				return;
			}
			final Node<K, T> node = children.get(idx);
			node.delete(key, order);
			pruneEmptyChild(idx, node);
		}

		@Override
		void deleteMatchingRowKey(T key, byte[] rowKey, int order) {
			final int idx = findIndex(key, true);
			if (idx >= children.size()) {
				return;
			}
			final Node<K, T> node = children.get(idx);
			node.deleteMatchingRowKey(key, rowKey, order);
			pruneEmptyChild(idx, node);
		}

		@Override
		void deletePointer(T key, IndexPointerRef pointerRef, int order) {
			final int idx = findIndex(key, true);
			if (idx >= children.size()) {
				return;
			}
			final Node<K, T> node = children.get(idx);
			node.deletePointer(key, pointerRef, order);
			pruneEmptyChild(idx, node);
		}

		/**
		 * Drop an empty leaf or empty internal child and its separator.
		 * Rightmost child ({@code idx == keys.size()}) removes {@code keys[idx - 1]};
		 * otherwise removes {@code keys[idx]}.
		 */
		private void pruneEmptyChild(int idx, Node<K, T> node) {
			final boolean emptyLeaf = node instanceof LeafNode<K, T> leaf && leaf.keys.isEmpty();
			final boolean emptyInternal = node instanceof InternalNode<K, T> internal && internal.children.isEmpty();
			if (!emptyLeaf && !emptyInternal) {
				return;
			}
			if (emptyLeaf) {
				final LeafNode<K, T> leafToRemove = (LeafNode<K, T>) node;
				if (leafToRemove.prev != null) {
					leafToRemove.prev.next = leafToRemove.next;
				}
				if (leafToRemove.next != null) {
					leafToRemove.next.prev = leafToRemove.prev;
				}
			}
			children.remove(idx);
			if (keys.isEmpty()) {
				return;
			}
			// B+ separator sits between children[i] and children[i+1]:
			// remove keys[idx] when pruning a non-rightmost child; keys[idx-1] for rightmost.
			if (idx < keys.size()) {
				keys.remove(idx);
			} else if (idx > 0) {
				keys.remove(idx - 1);
			}
			if (children.size() != keys.size() + 1) {
				throw new IllegalStateException("BPTree internal desync after pruneEmptyChild: children=" + children.size() + " keys=" + keys.size());
			}
		}

		@Override
		IndexOperationResult searchEq(T key) {
			if (key.isPartial()) {
				// Lower-bound into the first child that may hold the prefix, then leaf-walk.
				final int idx = findPrefixChildIndex(key);
				return idx < children.size() ? children.get(idx).searchEq(key) : null;
			}
			final int idx = findIndex(key, false);
			return idx < children.size() ? children.get(idx).searchEq(key) : null;
		}

		@Override
		IndexOperationResult searchEqLimited(T key, int maxResults) {
			if (key.isPartial()) {
				final int idx = findPrefixChildIndex(key);
				return idx < children.size() ? children.get(idx).searchEqLimited(key, maxResults) : null;
			}
			return searchEqLimitedExact(key, maxResults);
		}

		/**
		 * First child that may contain keys matching {@code key}'s prefix
		 * ({@code comparePartial <= 0} = search ≤ separator on compared dims).
		 */
		protected int findPrefixChildIndex(T key) {
			return lowerBoundLe(keys, key::comparePartial);
		}

		private IndexOperationResult searchEqLimitedExact(T key, int maxResults) {
			final int idx = findIndex(key, false);
			return idx < children.size() ? children.get(idx).searchEqLimited(key, maxResults) : null;
		}

		@Override
		IndexOperationResult searchGreaterThan(T key) {
			final int idx = findIndex(key, false);
			return children.get(idx).searchGreaterThan(key);
		}

		@Override
		IndexOperationResult searchGreaterThanOrEqual(T key) {
			final int idx = findIndex(key, false);
			return children.get(idx).searchGreaterThanOrEqual(key);
		}

		@Override
		IndexOperationResult searchLessThan(T key) {
			final int idx = findIndex(key, false);
			return children.get(idx).searchLessThan(key);
		}

		@Override
		IndexOperationResult searchLessThanOrEqual(T key) {
			final int idx = findIndex(key, false);
			return children.get(idx).searchLessThanOrEqual(key);
		}

		@Override
		IndexOperationResult searchRange(T low, T high) {
			final int idx = findIndex(low, false);
			return children.get(idx).searchRange(low, high);
		}

		@Override
		IndexOperationResult searchNotEqual(T key) {
			final int idx = findIndex(key, false);
			return children.get(idx).searchNotEqual(key);
		}

		@Override
		IndexOperationResult searchAll() {
			// B+ full scan: one left-to-right leaf walk. Do not fan out per child
			// (each LeafNode.searchAll follows next — that would be O(leaves^2)).
			if (children.isEmpty()) {
				final IndexOperationResult empty = new IndexOperationResult();
				empty.setRowsProcessed(0);
				empty.setSize(0);
				empty.setPointers(Set.of());
				return empty;
			}
			Node<K, T> node = children.getFirst();
			while (node instanceof InternalNode<K, T> internal) {
				if (internal.children.isEmpty()) {
					final IndexOperationResult empty = new IndexOperationResult();
					empty.setRowsProcessed(0);
					empty.setSize(0);
					empty.setPointers(Set.of());
					return empty;
				}
				node = internal.children.getFirst();
			}
			return node.searchAll();
		}

		@Override
		public void clear() {
			for (Node<K, T> child : children) {
				child.clear();
			}
			keys.clear();
			children.clear();
		}

		protected LeafSplit<K, T> split() {
			final int mid = keys.size() / 2;
			// Promote keys[mid] (classic B+ internal split). Promoting keys[mid-1]
			// drops the mid separator and routes a gap of keys to the wrong child.
			final T splitKey = keys.get(mid);
			final InternalNode<K, T> right = createInternalNode();
			right.keys.addAll(keys.subList(mid + 1, keys.size()));
			right.children.addAll(children.subList(mid + 1, children.size()));
			keys = new ArrayList<>(keys.subList(0, mid));
			children = new ArrayList<>(children.subList(0, mid + 1));
			return createLeafSplit(splitKey, right);
		}

		protected int findIndex(T key, boolean manage) {
			return lowerBoundLt(keys, mid -> manage ? key.compareFull(mid) : key.comparePartial(mid));
		}
	}


	public static abstract class LeafNode<K, T extends TreeKey<K>> extends Node<K, T> {
		public List<Set<IndexPointerRef>> values = new ArrayList<>();
		public LeafNode<K, T> next;
		public LeafNode<K, T> prev;

		@NonNull
		protected abstract LeafNode<K, T> createLeafNode();

		@NonNull
		protected abstract LeafSplit<K, T> createLeafSplit(T splitKey, LeafNode<K, T> right);

		@Override
		LeafSplit<K, T> insert(T key, IndexPointerRef pointerRef, int order, boolean strict) {
			int idx = indexedBinarySearch(keys, key, true);
			if (idx >= 0) {
				if (strict) {
					throw new NonUniqueValueException("Value [" + key + "] not unique for strict index.");
				}
				final Set<IndexPointerRef> valueList = values.get(idx);
				valueList.add(pointerRef);
				return null;
			}
			idx = -(idx + 1);
			keys.add(idx, key);
			final Set<IndexPointerRef> valueList = new FastOrderedSet<>(1);
			valueList.add(pointerRef);
			values.add(idx, valueList);
			if (keys.size() >= order) {
				return split();
			}
			return null;
		}

		@Override
		void delete(T key, int order) {
			final int idx = indexedBinarySearch(keys, key, true);
			if (idx >= 0) {
				cleanPtrs(values.get(idx));
				keys.remove(idx);
				values.remove(idx);
			}
		}

		@Override
		void deleteMatchingRowKey(T key, byte[] rowKey, int order) {
			final int idx = indexedBinarySearch(keys, key, true);
			if (idx < 0) {
				return;
			}
			final Set<IndexPointerRef> pointers = values.get(idx);
			final List<IndexPointerRef> doomed = new ArrayList<>();
			for (IndexPointerRef pointerRef : pointers) {
				if (pointerRef == null) {
					continue;
				}
				final byte[] resolved = pointerRef.resolveKey();
				if (Arrays.equals(resolved, rowKey)) {
					doomed.add(pointerRef);
				}
			}
			removePointers(idx, pointers, doomed);
		}

		@Override
		void deletePointer(T key, IndexPointerRef pointerRef, int order) {
			final int idx = indexedBinarySearch(keys, key, true);
			if (idx < 0 || pointerRef == null) {
				return;
			}
			final Set<IndexPointerRef> pointers = values.get(idx);
			removePointers(idx, pointers, List.of(pointerRef));
		}

		private void removePointers(int idx, Set<IndexPointerRef> pointers, List<IndexPointerRef> doomed) {
			for (IndexPointerRef pointerRef : doomed) {
				if (pointers.remove(pointerRef) && pointerRef.getPointer() != 0 && !pointerRef.isFree()) {
					pointerRef.free();
				}
			}
			if (pointers.isEmpty()) {
				keys.remove(idx);
				values.remove(idx);
			}
		}

		@Override
		void clear() {
			keys.clear();
			for (Set<IndexPointerRef> prts : values) {
				cleanPtrs(prts);
			}
			values.clear();
		}

		@Override
		public IndexOperationResult searchEq(T key) {
			final IndexOperationResult operationResult = new IndexOperationResult();
			if (key.isPartial()) {
				final PointerSetAccumulator acc = new PointerSetAccumulator();
				LeafNode<K, T> current = this;
				int start = lowerBoundPartial(key);
				while (current != null) {
					for (int i = start; i < current.keys.size(); i++) {
						final T leafKey = current.keys.get(i);
						final int cmp = key.comparePartial(leafKey);
						if (cmp < 0) {
							// Past prefix range — stop (tree is ordered).
							operationResult.setPointers(acc.get());
							return operationResult;
						}
						if (cmp == 0) {
							final Set<IndexPointerRef> currentPointers = current.values.get(i);
							acc.addAll(currentPointers);
							operationResult.addSize(currentPointers.size());
						}
						operationResult.incProcessed();
					}
					start = 0;
					current = current.next;
				}
				operationResult.setPointers(acc.get());
				return operationResult;
			}
			final int idx = indexedBinarySearch(keys, key, false);
			if (idx >= 0) {
				final Set<IndexPointerRef> currentPointers = values.get(idx);
				operationResult.addSize(currentPointers.size());
				operationResult.setPointers(currentPointers);
			}
			return operationResult;
		}

		@Override
		IndexOperationResult searchEqLimited(T key, int maxResults) {
			if (maxResults <= 0) {
				return searchEq(key);
			}
			if (!key.isPartial()) {
				final IndexOperationResult operationResult = new IndexOperationResult();
				final int idx = indexedBinarySearch(keys, key, false);
				if (idx < 0) {
					return operationResult;
				}
				final Set<IndexPointerRef> currentPointers = values.get(idx);
				if (currentPointers == null || currentPointers.isEmpty()) {
					return operationResult;
				}
				if (currentPointers.size() <= maxResults) {
					operationResult.addSize(currentPointers.size());
					operationResult.setPointers(currentPointers);
					operationResult.incProcessed();
					return operationResult;
				}
				final PointerSetAccumulator acc = new PointerSetAccumulator(maxResults);
				acc.addAll(currentPointers);
				operationResult.setPointers(acc.get());
				operationResult.setSize(acc.get() != null ? acc.get().size() : 0);
				operationResult.incProcessed();
				return operationResult;
			}
			final IndexOperationResult operationResult = new IndexOperationResult();
			final PointerSetAccumulator acc = new PointerSetAccumulator(maxResults);
			LeafNode<K, T> current = this;
			int start = lowerBoundPartial(key);
			while (current != null && !acc.isFull()) {
				for (int i = start; i < current.keys.size(); i++) {
					final T leafKey = current.keys.get(i);
					final int cmp = key.comparePartial(leafKey);
					if (cmp < 0) {
						operationResult.setPointers(acc.get());
						if (acc.get() != null) {
							operationResult.setSize(acc.get().size());
						}
						return operationResult;
					}
					if (cmp == 0) {
						final Set<IndexPointerRef> currentPointers = current.values.get(i);
						if (!acc.addAll(currentPointers)) {
							operationResult.incProcessed();
							operationResult.setPointers(acc.get());
							operationResult.setSize(acc.get().size());
							return operationResult;
						}
						operationResult.addSize(currentPointers.size());
					}
					operationResult.incProcessed();
				}
				start = 0;
				current = current.next;
			}
			operationResult.setPointers(acc.get());
			if (acc.get() != null) {
				operationResult.setSize(acc.get().size());
			}
			return operationResult;
		}

		/**
		 * First index where {@code key.comparePartial(keys[i]) <= 0}.
		 */
		private int lowerBoundPartial(T key) {
			return lowerBoundLe(keys, key::comparePartial);
		}

		@Override
		IndexOperationResult searchGreaterThan(T key) {
			final IndexOperationResult operationResult = new IndexOperationResult();
			final PointerSetAccumulator acc = new PointerSetAccumulator();
			LeafNode<K, T> current = this;
			while (current != null) {
				for (int i = 0; i < current.keys.size(); i++) {
					final T next = current.keys.get(i);
					if (next == null || next.comparePartial(key) > 0) {
						final Set<IndexPointerRef> currentPointers = current.values.get(i);
						acc.addAll(currentPointers);
						operationResult.addSize(currentPointers.size());
					}
					operationResult.incProcessed();
				}
				current = current.next;
			}
			operationResult.setPointers(acc.get());
			return operationResult;
		}

		@Override
		IndexOperationResult searchGreaterThanOrEqual(T key) {
			final IndexOperationResult operationResult = new IndexOperationResult();
			final PointerSetAccumulator acc = new PointerSetAccumulator();
			LeafNode<K, T> current = this;
			while (current != null) {
				for (int i = 0; i < current.keys.size(); i++) {
					final T next = current.keys.get(i);
					if (next == null || next.comparePartial(key) >= 0) {
						final Set<IndexPointerRef> currentPointers = current.values.get(i);
						acc.addAll(currentPointers);
						operationResult.addSize(currentPointers.size());
					}
					operationResult.incProcessed();
				}
				current = current.next;
			}
			operationResult.setPointers(acc.get());
			return operationResult;
		}

		@Override
		IndexOperationResult searchLessThan(T key) {
			final IndexOperationResult operationResult = new IndexOperationResult();
			final PointerSetAccumulator acc = new PointerSetAccumulator();
			LeafNode<K, T> current = this;
			while (current != null) {
				for (int i = 0; i < current.keys.size(); i++) {
					final T next = current.keys.get(i);
					if (next != null && next.comparePartial(key) < 0) {
						final Set<IndexPointerRef> currentPointers = current.values.get(i);
						acc.addAll(currentPointers);
						operationResult.addSize(currentPointers.size());
					}
					operationResult.incProcessed();
				}
				current = current.next;
			}
			operationResult.setPointers(acc.get());
			return operationResult;
		}

		@Override
		IndexOperationResult searchLessThanOrEqual(T key) {
			final IndexOperationResult operationResult = new IndexOperationResult();
			final PointerSetAccumulator acc = new PointerSetAccumulator();
			LeafNode<K, T> current = this;
			while (current != null) {
				for (int i = 0; i < current.keys.size(); i++) {
					final T next = current.keys.get(i);
					if (next != null && next.comparePartial(key) <= 0) {
						final Set<IndexPointerRef> currentPointers = current.values.get(i);
						acc.addAll(currentPointers);
						operationResult.addSize(currentPointers.size());
					}
					operationResult.incProcessed();
				}
				current = current.next;
			}
			operationResult.setPointers(acc.get());
			return operationResult;
		}

		@Override
		public IndexOperationResult searchRange(T low, T high) {
			final IndexOperationResult operationResult = new IndexOperationResult();
			final PointerSetAccumulator acc = new PointerSetAccumulator();
			LeafNode<K, T> current = this;
			while (current != null) {
				for (int i = 0; i < current.keys.size(); i++) {
					final T next = current.keys.get(i);
					if (next != null && next.comparePartial(low) >= 0 && next.comparePartial(high) <= 0) {
						final Set<IndexPointerRef> currentPointers = current.values.get(i);
						acc.addAll(currentPointers);
						operationResult.addSize(currentPointers.size());
					}
					operationResult.incProcessed();
				}
				current = current.next;
			}
			operationResult.setPointers(acc.get());
			return operationResult;
		}

		@Override
		IndexOperationResult searchNotEqual(T key) {
			final IndexOperationResult operationResult = new IndexOperationResult();
			final PointerSetAccumulator acc = new PointerSetAccumulator();
			LeafNode<K, T> current = this;
			while (current != null) {
				for (int i = 0; i < current.keys.size(); i++) {
					if (!current.keys.get(i).equals(key)) {
						final Set<IndexPointerRef> currentPointers = current.values.get(i);
						acc.addAll(currentPointers);
						operationResult.addSize(currentPointers.size());
					}
					operationResult.incProcessed();
				}
				current = current.next;
			}
			operationResult.setPointers(acc.get());
			return operationResult;
		}

		@Override
		IndexOperationResult searchAll() {
			final IndexOperationResult operationResult = new IndexOperationResult();
			final Set<IndexPointerRef> result = new HashSet<>();
			LeafNode<K, T> current = this;
			while (current != null) {
				if (current.keys.size() != current.values.size()) {
					throw new IllegalStateException("BPTree leaf desync: keys=" + current.keys.size() + " values=" + current.values.size());
				}
				for (int i = 0; i < current.keys.size(); i++) {
					final Set<IndexPointerRef> currentPointers = current.values.get(i);
					if (currentPointers != null) {
						result.addAll(currentPointers);
					}
				}
				current = current.next;
			}
			operationResult.setRowsProcessed(result.size());
			operationResult.setSize(result.size());
			operationResult.setPointers(result);
			return operationResult;
		}

		private void cleanPtrs(Set<IndexPointerRef> values) {
			for (IndexPointerRef pointerRef : values) {
				if (pointerRef == null || pointerRef.getPointer() == 0) {
					continue;
				}
				pointerRef.free();
			}
		}

		private LeafSplit<K, T> split() {
			final int mid = keys.size() / 2;
			final LeafNode<K, T> right = createLeafNode();
			right.keys.addAll(keys.subList(mid, keys.size()));
			right.values.addAll(values.subList(mid, values.size()));
			right.next = this.next;
			right.prev = this;
			if (this.next != null) {
				this.next.prev = right;
			}
			this.next = right;
			keys = new ArrayList<>(keys.subList(0, mid));
			values = new ArrayList<>(values.subList(0, mid));
			return createLeafSplit(right.keys.getFirst(), right);
		}

		private int indexedBinarySearch(List<T> list, T key, boolean manage) {
			int low = 0;
			int high = list.size() - 1;
			while (low <= high) {
				final int mid = (low + high) >>> 1;
				final T midVal = list.get(mid);
				final int cmp = manage ? midVal.compareFull(key) : midVal.comparePartial(key);
				if (cmp < 0) {
					low = mid + 1;
				} else if (cmp > 0) {
					high = mid - 1;
				} else {
					return mid; // key found
				}
			}
			return -(low + 1); // key not found
		}
	}


	public interface LeafSplit<K, T extends TreeKey<K>> {
		Node<K, T> getRight();

		T getSplitKey();
	}


	public static class IndexStat {
		public int nodeCount;
		public int internalNodeCount;
		public int leafNodeCount;
		public int totalKeys;
		public long totalRecords;
		public long cardinality;
		public long totalMemory;
		public Map<Integer, Long> memoryByDepth = new HashMap<>();

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder();
			sb.append(String.format("Tree stats:%n" + "- nodes: %d%n" + "- internal: %d%n" + "- leaf: %d%n" + "- keys: %d%n" + "- records: %d%n" + "- cardinality: %d%n" + "- memory: %d B (%.2f KiB, %.2f MiB)%n%n", nodeCount, internalNodeCount, leafNodeCount, totalKeys, totalRecords, cardinality, totalMemory, totalMemory / 1024.0, totalMemory / (1024.0 * 1024.0)));
			sb.append("Memory by depth:\n");
			for (Map.Entry<Integer, Long> entry : memoryByDepth.entrySet()) {
				sb.append(String.format("- depth %d: %d B (%.2f KiB)%n", entry.getKey(), entry.getValue(), entry.getValue() / 1024.0));
			}
			return sb.toString();
		}

		public long getCardinality() {
			return this.cardinality;
		}
	}


	public static class FastOrderedSet<E> implements Set<E>, List<E> {
		private final Set<E> backedSet;
		private final List<E> backedList;

		public FastOrderedSet(int initialSize) {
			backedSet = new HashSet<>(Math.max(4, initialSize));
			backedList = new ArrayList<>(Math.max(4, initialSize));
		}

		@Override
		public int size() {
			return backedList.size();
		}

		@Override
		public boolean isEmpty() {
			return backedList.isEmpty();
		}

		@Override
		public boolean contains(Object o) {
			return backedSet.contains(o);
		}

		@Override
		@NonNull
		public Iterator<E> iterator() {
			return new Iterator<>() {
				private final Iterator<E> listIterator = backedList.iterator();
				private E current;
				@Override
				public boolean hasNext() {
					return listIterator.hasNext();
				}
				@Override
				public E next() {
					current = listIterator.next();
					return current;
				}
				@Override
				public void remove() {
					listIterator.remove();
					backedSet.remove(current);
				}
			};
		}

		@Override
		public void forEach(Consumer<? super E> action) {
			backedList.forEach(action);
		}

		@Override
		@NonNull
		public Object[] toArray() {
			return backedList.toArray();
		}

		@Override
		@NonNull
		public <T> T[] toArray(@NonNull T[] a) {
			return backedList.toArray(a);
		}

		@Override
		public <T> T[] toArray(IntFunction<T[]> generator) {
			return backedList.toArray(generator);
		}

		@Override
		public int indexOf(Object o) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int lastIndexOf(Object o) {
			throw new UnsupportedOperationException();
		}

		@Override
		@NonNull
		public ListIterator<E> listIterator() {
			return backedList.listIterator();
		}

		@Override
		@NonNull
		public ListIterator<E> listIterator(int index) {
			return backedList.listIterator(index);
		}

		@Override
		@NonNull
		public List<E> subList(int fromIndex, int toIndex) {
			return backedList.subList(fromIndex, toIndex);
		}

		@Override
		public boolean add(E e) {
			if (backedSet.add(e)) {
				backedList.add(e);
				return true;
			}
			return false;
		}

		@Override
		public boolean remove(Object o) {
			if (backedSet.remove(o)) {
				backedList.remove(o);
				return true;
			}
			return false;
		}

		@Override
		public boolean containsAll(Collection<?> c) {
			return backedSet.containsAll(c);
		}

		@Override
		public boolean addAll(Collection<? extends E> c) {
			boolean modified = false;
			for (E item : c) {
				if (add(item)) {
					modified = true;
				}
			}
			return modified;
		}

		@Override
		public boolean addAll(int index, Collection<? extends E> c) {
			synchronized (this) {
				if (index < 0 || index > size()) {
					throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
				}
				if (c == null || c.isEmpty()) {
					return false;
				}
				final List<E> elementsToAdd = new ArrayList<>();
				for (E element : c) {
					if (!contains(element)) {
						elementsToAdd.add(element);
					}
				}
				if (elementsToAdd.isEmpty()) {
					return false;
				}
				backedSet.addAll(elementsToAdd);
				backedList.addAll(index, elementsToAdd);
				return true;
			}
		}

		@Override
		@NonNull
		public boolean retainAll(Collection<?> c) {
			if (backedSet.retainAll(c)) {
				backedList.retainAll(c);
				return true;
			}
			return false;
		}

		@Override
		public void replaceAll(UnaryOperator<E> operator) {
			final List<E> newValues = new ArrayList<>(backedList.size());
			for (E element : backedList) {
				final E newValue = operator.apply(element);
				if (!backedSet.contains(newValue) && !newValue.equals(element)) {
					throw new IllegalArgumentException("Duplicate element: " + newValue);
				}
				newValues.add(newValue);
			}
			backedList.clear();
			backedSet.clear();
			for (E newValue : newValues) {
				backedList.add(newValue);
				backedSet.add(newValue);
			}
		}

		@Override
		public void sort(Comparator<? super E> c) {
			backedList.sort(c);
			backedSet.clear();
		}

		@Override
		public boolean removeAll(Collection<?> c) {
			if (backedSet.removeAll(c)) {
				backedList.removeAll(c);
				return true;
			}
			return false;
		}

		@Override
		public boolean removeIf(Predicate<? super E> filter) {
			final List<E> toRemove = new ArrayList<>();
			for (E e : backedList) {
				if (filter.test(e)) {
					toRemove.add(e);
				}
			}
			if (toRemove.isEmpty()) {
				return false;
			}
			for (E e : toRemove) {
				if (backedSet.remove(e)) {
					backedList.remove(e);
				}
			}
			return true;
		}

		@Override
		public void clear() {
			backedSet.clear();
			backedList.clear();
		}

		@Override
		public Spliterator<E> spliterator() {
			return backedList.spliterator();
		}

		@Override
		public Stream<E> stream() {
			return backedList.stream();
		}

		@Override
		public Stream<E> parallelStream() {
			throw new UnsupportedOperationException("parallelStream binds to the JDK common ForkJoin pool; use ThreadService.getCpuExecutor()");
		}

		@Override
		public E get(int index) {
			return backedList.get(index);
		}

		@Override
		public E set(int index, E element) {
			backedSet.add(element);
			return backedList.set(index, element);
		}

		@Override
		public void add(int index, E element) {
			if (index < 0 || index > backedList.size()) {
				throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + backedList.size());
			}
			if (backedSet.add(element)) {
				backedList.add(index, element);
			}
		}

		@Override
		public E remove(int index) {
			final E removedElement = backedList.remove(index);
			if (removedElement != null) {
				backedSet.remove(removedElement);
				return removedElement;
			}
			return null;
		}
	}

	public String getIndexName() {
		return this.indexName;
	}

	public IndexStat getIndexStat() {
		return this.indexStat;
	}
}
