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
package org.genfork.grid.mem.stage;

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.utils.ArrayUtil;
import org.springframework.util.CollectionUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * Sharded staged write pipeline: queue → worker → (optional commitListener) → map → index.
 * {@link #add} / {@link #remove} return futures that complete only after map commit.
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class GridEntriesProcessor {
	private static final Comparator<Entry> ENTRY_COMPARATOR = Comparator.comparingLong(Entry::getStoreTime);


	public static class Entry {
		private final byte[] key;
		private final byte[] value;
		private final CompletableFuture<Void> committed = new CompletableFuture<>();
		private final long storeTime = System.currentTimeMillis();

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Entry entry = (Entry) o;
			if (storeTime != entry.storeTime) return false;
			if (!Arrays.equals(key, entry.key)) return false;
			return Arrays.equals(value, entry.value);
		}

		@Override
		public int hashCode() {
			int result = ArrayUtil.fastHash(key);
			result = 31 * result + ArrayUtil.fastHash(value);
			result = 31 * result + Long.hashCode(storeTime);
			return result;
		}

		public Entry(final byte[] key, final byte[] value) {
			this.key = key;
			this.value = value;
		}

		public byte[] getKey() {
			return this.key;
		}

		public byte[] getValue() {
			return this.value;
		}

		public CompletableFuture<Void> getCommitted() {
			return this.committed;
		}

		public long getStoreTime() {
			return this.storeTime;
		}

		@java.lang.Override
		public java.lang.String toString() {
			return "GridEntriesProcessor.Entry(key=" + java.util.Arrays.toString(this.getKey()) + ", value=" + java.util.Arrays.toString(this.getValue()) + ", committed=" + this.getCommitted() + ", storeTime=" + this.getStoreTime() + ")";
		}
	}


	public record KeyEntry(byte[] key) {
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			KeyEntry keyEntry = (KeyEntry) o;
			return Arrays.equals(key, keyEntry.key);
		}

		@Override
		public int hashCode() {
			return ArrayUtil.fastHash(key);
		}
	}


	public static class AddEntry extends Entry {
		private final Object domain;

		public AddEntry(Object domain, byte[] key, byte[] value) {
			super(key, value);
			this.domain = domain;
		}

		public Object getDomain() {
			return this.domain;
		}
	}


	public static class RemoveEntry extends Entry {
		public RemoveEntry(byte[] key) {
			super(key, null);
		}
	}

	private static AddEntry addEntry(Object domain, byte[] key, byte[] value) {
		return new AddEntry(domain, key, value);
	}

	private static RemoveEntry removeEntry(byte[] key) {
		return new RemoveEntry(key);
	}

	private final Map<KeyEntry, Entry> stagingArea = new ConcurrentHashMap<>(8);
	private final WrapTableQueue queue = new WrapTableQueue();
	private final int shardNum;
	private final GridScalableMap gridScalableMap;
	private final GridCompositeIndex index;

	public GridCompositeIndex compositeIndex() {
		return index;
	}

	private final GridIndexWorker gridIndexWorker;
	private CommitListener commitListener;
	private BatchCommitListener batchCommitListener;
	private Runnable wakeSignal;
	/**
	 * Optional mmap sealed twin for miss path (LAZY / eviction).
	 */
	private SealedGridMapReader sealedReader;

	/**
	 * Sealed directory entry hint for {@link org.genfork.grid.query.plan.QueryCardinality} (0 if none).
	 */
	public long sealedEntryHint() {
		final SealedGridMapReader reader = sealedReader;
		return reader == null ? 0L : reader.entryCount();
	}

	/**
	 * Optional working-set budget (0 = unlimited). Shared across shards of a table when set.
	 */
	private WorkingSetBudget workingSetBudget;

	public GridEntriesProcessor(int shardNum, GridScalableMap gridScalableMap, GridCompositeIndex index, GridIndexWorker gridIndexWorker) {
		this.shardNum = shardNum;
		this.gridScalableMap = gridScalableMap;
		this.index = index;
		this.gridIndexWorker = gridIndexWorker;
	}

	/**
	 * Stream committed map entries without materializing a heap snapshot.
	 * <p>
	 * <b>Product policy:</b> do not pair with per-key sync {@code indexNow} or other heavy work
	 * on VT | Reactor | Netty. Prefer sealed ∪ OpLog-delta batch index, or chunked mailbox ops.
	 */
	public void forEachCommitted(BiConsumer<byte[], byte[]> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		for (Map.Entry<byte[], byte[]> e : gridScalableMap.entrySet()) {
			final byte[] key = e.getKey();
			final byte[] value = e.getValue();
			if (key != null && value != null) {
				consumer.accept(key, value);
			}
		}
	}

	/**
	 * Map put/remove without staging or index queue (LAZY OpLog hydrate delta path).
	 */
	public void installMapOnly(byte[] key, byte[] value, boolean delete) {
		if (key == null) {
			return;
		}
		if (delete) {
			removeMapOnly(key);
		} else {
			putMapOnly(key, value);
		}
	}

	/**
	 * Index a hydrate / backfill delta in one mailbox round-trip (not full map scan).
	 */
	public void indexDeltaNow(List<AddEntry> adds) {
		if (gridIndexWorker == null || adds == null || adds.isEmpty()) {
			return;
		}
		gridIndexWorker.indexNowBatch(adds);
	}

	/**
	 * Sync index remove after map-only delete (hydrate DELETE delta).
	 */
	public void indexNowRemove(byte[] key) {
		if (gridIndexWorker == null || key == null) {
			return;
		}
		gridIndexWorker.indexNowRemove(key);
	}


	@FunctionalInterface
	public interface CommitListener {
		void onCommitted(int shardNum, Entry entry);
	}


	/**
	 * Optional: one OpLog group-fsync for the whole drain batch.
	 */
	@FunctionalInterface
	public interface BatchCommitListener {
		void onCommittedBatch(int shardNum, List<Entry> entries);
	}

	public String report() {
		return String.format("# %d [size=%d pending_size=%d staging_size=%d]", shardNum, gridScalableMap.size(), queue.size(), stagingArea.size());
	}

	public boolean containsKey(byte[] key) {
		if (stagingArea.containsKey(new KeyEntry(key))) {
			return true;
		}
		if (gridScalableMap.containsKey(key)) {
			return true;
		}
		return sealedGet(key) != null;
	}

	public byte[] get(byte[] key) {
		if (stagingArea.isEmpty()) {
			return getCommitted(key);
		}
		final Entry entry = stagingArea.get(new KeyEntry(key));
		if (entry != null) {
			return entry.getValue();
		}
		return getCommitted(key);
	}

	/**
	 * Committed map only (skip staging) — for linearizable / Jepsen reads. Miss → sealed mmap.
	 */
	public byte[] getCommitted(byte[] key) {
		final byte[] inMap = gridScalableMap.get(key);
		if (inMap != null) {
			touchWorkingSet(key);
			return inMap;
		}
		final byte[] fromSealed = sealedGet(key);
		if (fromSealed == null) {
			return null;
		}
		if (fromSealed.length == 0) {
			return null;
		}
		ReplicationMetrics.recordSealedMiss();
		final WorkingSetBudget budget = workingSetBudget;
		if (budget != null && budget.preferSealedOnly()) {
			// Adaptive HIGH: disk-first read — serve sealed bytes without warming RAM.
			return fromSealed;
		}
		loadIntoWorkingSet(key, fromSealed);
		return fromSealed;
	}

	private byte[] sealedGet(byte[] key) {
		final SealedGridMapReader reader = sealedReader;
		if (reader == null) {
			return null;
		}
		return reader.get(key);
	}

	private void loadIntoWorkingSet(byte[] key, byte[] value) {
		gridScalableMap.put(key, value);
		if (gridIndexWorker != null) {
			gridIndexWorker.indexNow(key, value);
		}
		touchWorkingSet(key);
	}

	private void touchWorkingSet(byte[] key) {
		final WorkingSetBudget budget = workingSetBudget;
		if (budget != null) {
			budget.touch(shardNum, key);
		}
	}

	/**
	 * Evict one committed key from this shard's RAM map (index pointers dropped).
	 */
	public void evictCommitted(byte[] key) {
		if (key == null || stagingArea.containsKey(new KeyEntry(key))) {
			return;
		}
		final byte[] removed = gridScalableMap.remove(key);
		if (removed != null && gridIndexWorker != null) {
			gridIndexWorker.add(removeEntry(key));
		}
		final WorkingSetBudget budget = workingSetBudget;
		if (budget != null) {
			budget.remove(key);
		}
	}

	/**
	 * Enqueue upsert; future completes after map commit (or exceptionally on permanent fail).
	 */
	public CompletableFuture<Void> add(Object domain, byte[] key, byte[] value) {
		final AddEntry entry = addEntry(domain, key, value);
		queue.addLast(entry);
		stagingArea.put(new KeyEntry(entry.getKey()), entry);
		signalWake();
		return entry.getCommitted();
	}

	/**
	 * Enqueue delete; future completes after map commit.
	 */
	public CompletableFuture<Void> remove(byte[] key) {
		final RemoveEntry entry = removeEntry(key);
		queue.addLast(entry);
		stagingArea.put(new KeyEntry(entry.getKey()), entry);
		signalWake();
		return entry.getCommitted();
	}

	/**
	 * Block until this shard has empty queue and staging (all enqueued work committed).
	 */
	public void awaitIdle(long timeoutMs) throws InterruptedException, TimeoutException {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while (queue.size() > 0 || !stagingArea.isEmpty()) {
			if (System.nanoTime() >= deadline) {
				throw new TimeoutException("Shard " + shardNum + " still busy queue=" + queue.size() + " staging=" + stagingArea.size());
			}
			signalWake();
			Thread.sleep(1);
		}
	}

	public boolean isIdle() {
		return queue.size() == 0 && stagingArea.isEmpty();
	}

	public int mapSize() {
		return gridScalableMap.size();
	}

	public int size() {
		return stagingArea.size() + gridScalableMap.size();
	}

	/**
	 * Keys visible for table-scan queries (staging overrides map).
	 */
	public Set<byte[]> keys() {
		final Set<byte[]> keys = new HashSet<>(gridScalableMap.keySet());
		for (KeyEntry ke : stagingArea.keySet()) {
			keys.add(ke.key());
		}
		return keys;
	}

	public void reset() {
		queue.clear();
		for (Entry e : stagingArea.values()) {
			e.getCommitted().completeExceptionally(new IllegalStateException("processor reset"));
		}
		stagingArea.clear();
		if (index != null) {
			index.clear();
		}
		gridScalableMap.clear();
	}

	public void destroy() {
		gridScalableMap.destroy();
	}

	private void signalWake() {
		final Runnable wake = wakeSignal;
		if (wake != null) {
			wake.run();
		}
	}

	private void sort(List<Entry> entries) {
		if (CollectionUtils.isEmpty(entries)) {
			return;
		}
		if (entries.size() > 1) {
			entries.sort(ENTRY_COMPARATOR);
		}
	}

	public void process(List<Entry> entries) {
		final List<Entry> ordered = entries == null || entries.isEmpty() ? entries : new ArrayList<>(entries);
		sort(ordered);
		final List<Entry> coalesced = coalesceByKeyLatest(ordered);
		if (coalesced == null || coalesced.isEmpty()) {
			return;
		}
		List<Entry> toApply = coalesced;
		if (batchCommitListener != null) {
			try {
				batchCommitListener.onCommittedBatch(shardNum, coalesced);
			} catch (RuntimeException ex) {
				// Re-queue entire batch; keep staging — no silent loss.
				for (Entry entry : coalesced) {
					queue.addLast(entry);
				}
				signalWake();
				return;
			}
		} else if (commitListener != null) {
			final List<Entry> ok = new ArrayList<>(coalesced.size());
			for (Entry entry : coalesced) {
				try {
					commitListener.onCommitted(shardNum, entry);
					ok.add(entry);
				} catch (RuntimeException ex) {
					queue.addLast(entry);
					signalWake();
				}
			}
			toApply = ok;
		}
		final Set<Entry> applied = new HashSet<>(Math.max(16, toApply.size() * 2));
		for (Entry entry : toApply) {
			final byte[] key = entry.getKey();
			final KeyEntry ke = new KeyEntry(key);
			try {
				if (entry instanceof AddEntry) {
					gridScalableMap.put(key, entry.getValue());
					if (gridIndexWorker != null) {
						gridIndexWorker.add(entry);
					}
					touchWorkingSet(key);
				} else {
					final byte[] removed = gridScalableMap.remove(key);
					if (removed != null && gridIndexWorker != null) {
						gridIndexWorker.add(entry);
					}
				}
				// Drop staging only if this entry is still the visible staged version.
				stagingArea.compute(ke, (_, cur) -> cur == entry ? null : cur);
				entry.getCommitted().complete(null);
				applied.add(entry);
			} catch (RuntimeException ex) {
				queue.addLast(entry);
				signalWake();
			}
		}
		// Coalesced-away same-key predecessors: map already has the latest from this batch.
		if (ordered != null && ordered.size() > applied.size()) {
			final Set<KeyEntry> appliedKeys = new HashSet<>();
			for (Entry e : applied) {
				appliedKeys.add(new KeyEntry(e.getKey()));
			}
			for (Entry entry : ordered) {
				if (!entry.getCommitted().isDone() && appliedKeys.contains(new KeyEntry(entry.getKey()))) {
					entry.getCommitted().complete(null);
				}
			}
		}
	}

	/**
	 * Same-key UPSERT coalesce: only the latest queue entry per key is committed/shipped.
	 */
	private static List<Entry> coalesceByKeyLatest(List<Entry> ordered) {
		if (ordered == null || ordered.size() <= 1) {
			return ordered;
		}
		final Map<KeyEntry, Entry> latest = new LinkedHashMap<>();
		for (Entry entry : ordered) {
			final KeyEntry k = new KeyEntry(entry.getKey());
			latest.remove(k);
			latest.put(k, entry);
		}
		return new ArrayList<>(latest.values());
	}

	/**
	 * Synchronous map (+ optional index) install without staging or commit listener.
	 * <p>
	 * Index updates use {@link GridIndexWorker#indexNow} / {@link GridIndexWorker#indexNowRemove}
	 * so {@code SELECT *} (PK {@code searchAll}) observes the row immediately — async enqueue
	 * left map/sealed visible to INSERT duplicate checks while the PK tree stayed empty.
	 */
	public void installCommitted(byte[] key, byte[] value, boolean delete) {
		if (key == null) {
			return;
		}
		if (delete) {
			final byte[] removed = gridScalableMap.remove(key);
			if (removed != null && gridIndexWorker != null) {
				gridIndexWorker.indexNowRemove(key);
			}
		} else {
			gridScalableMap.put(key, value);
			if (gridIndexWorker != null) {
				gridIndexWorker.indexNow(key, value);
			}
			touchWorkingSet(key);
		}
	}

	/**
	 * Map put only (no staging / index queue). Pair with {@link GridIndexWorker#indexNow}.
	 */
	public void putMapOnly(byte[] key, byte[] value) {
		if (key == null) {
			return;
		}
		gridScalableMap.put(key, value);
		touchWorkingSet(key);
	}

	/**
	 * Map remove only (no staging / index queue). Pair with sync index drop.
	 */
	public byte[] removeMapOnly(byte[] key) {
		if (key == null) {
			return null;
		}
		final byte[] removed = gridScalableMap.remove(key);
		final WorkingSetBudget budget = workingSetBudget;
		if (budget != null) {
			budget.remove(key);
		}
		return removed;
	}

	public WrapTableQueue getQueue() {
		return this.queue;
	}

	public int getShardNum() {
		return this.shardNum;
	}

	public void setCommitListener(final CommitListener commitListener) {
		this.commitListener = commitListener;
	}

	public void setBatchCommitListener(final BatchCommitListener batchCommitListener) {
		this.batchCommitListener = batchCommitListener;
	}

	public void setWakeSignal(final Runnable wakeSignal) {
		this.wakeSignal = wakeSignal;
	}

	/**
	 * Optional mmap sealed twin for miss path (LAZY / eviction).
	 */
	public void setSealedReader(final SealedGridMapReader sealedReader) {
		this.sealedReader = sealedReader;
	}

	/**
	 * Optional mmap sealed twin for miss path (LAZY / eviction).
	 */
	public SealedGridMapReader getSealedReader() {
		return this.sealedReader;
	}

	/**
	 * Optional working-set budget (0 = unlimited). Shared across shards of a table when set.
	 */
	public void setWorkingSetBudget(final WorkingSetBudget workingSetBudget) {
		this.workingSetBudget = workingSetBudget;
	}
}
