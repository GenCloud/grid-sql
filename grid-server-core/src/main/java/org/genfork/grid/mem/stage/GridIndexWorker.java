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

import com.google.common.annotations.VisibleForTesting;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.mem.index.btree.BPValueHelper;
import org.genfork.grid.mem.stage.GridEntriesProcessor.AddEntry;
import org.genfork.grid.mem.stage.GridEntriesProcessor.Entry;
import org.genfork.grid.mem.stage.GridEntriesProcessor.RemoveEntry;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.FieldMetaData;
import org.genfork.grid.serial.HashField;
import org.genfork.grid.threading.SerialTaskQueue;
import org.genfork.grid.threading.ThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Index worker: SQL-first {@link TableSchema} path only (blob / column values → secondary indexes).
 * <p>
 * BPTree mutations are serialized via {@link SerialTaskQueue} on the logic VT pool —
 * no {@code synchronized} / monitor wait on VT | Reactor | Netty callers.
 * Async {@link #run()} is a background drain job; {@code indexNow*} await the mailbox
 * with {@link CompletableFuture#join()} (park, not pin).
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class GridIndexWorker implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(GridIndexWorker.class);

	private final AtomicBoolean running = new AtomicBoolean();

	private final WrapTableQueue queue = new WrapTableQueue();

	private final GridCompositeIndex index;
	private volatile TableSchema tableSchema;

	/** Serial mailbox for all BPTree put/remove (async drain + sync indexNow*). */
	private final SerialTaskQueue mutationQueue = new SerialTaskQueue();

	/** Re-entrancy when the mailbox drain thread itself calls into mutation helpers. */
	private static final ThreadLocal<Boolean> ON_INDEX_MAILBOX = new ThreadLocal<>();
	private static final int FIELD_MAP_INITIAL_CAPACITY = 8;
	private static final ThreadLocal<LinkedHashMap<String, byte[]>> FIELD_VALUES_SCRATCH =
			ThreadLocal.withInitial(() -> new LinkedHashMap<>(FIELD_MAP_INITIAL_CAPACITY));

	public GridIndexWorker(GridCompositeIndex index, TableSchema tableSchema) {
		this.index = index;
		this.tableSchema = Objects.requireNonNull(tableSchema, "tableSchema");
	}

	public void replaceSchema(TableSchema schema) {
		this.tableSchema = Objects.requireNonNull(schema, "schema");
	}

	public void add(Entry entry) {
		queue.addLast(entry);
	}

	/**
	 * Pending async index entries (background {@link #run} drain). Product SQL and
	 * {@code installCommitted} / sealed miss warm use sync {@code indexNow*}; this depth
	 * is for remaining staged queue paths only.
	 */
	@VisibleForTesting
	public int pendingQueueSize() {
		return queue.size();
	}

	/**
	 * Synchronous batch index upsert (one mailbox round-trip for the whole list).
	 * Prefer this over N×{@link #indexNow} on hydrate / backfill paths.
	 */
	public void indexNowBatch(List<AddEntry> adds) {
		if (adds == null || adds.isEmpty()) {
			return;
		}
		runMutationAndAwait(() -> {
			for (AddEntry addEntry : adds) {
				if (addEntry == null) {
					continue;
				}
				try {
					upsertIndexValuesFromSchemaUnlocked(addEntry);
				} catch (RuntimeException ex) {
					if (ex instanceof NonUniqueValueException) {
						log.warn("STRICT index upsert failed (indexNowBatch) keyHash={}: {}",
								addEntry.getKey() == null ? -1 : java.util.Arrays.hashCode(addEntry.getKey()),
								ex.toString());
						throw ex;
					}
					throw ex;
				}
			}
			index.startAnalyze();
		});
	}

	/**
	 * CREATE INDEX backfill: one mailbox for a chunk of (key, blob) pairs into a single named index.
	 */
	public void indexNowForIndexBatch(org.genfork.grid.catalog.IndexDef def, List<byte[]> keys, List<byte[]> values) {
		if (def == null || keys == null || values == null || keys.isEmpty()) {
			return;
		}
		if (keys.size() != values.size()) {
			throw new IllegalArgumentException("keys/values size mismatch");
		}
		runMutationAndAwait(() -> {
			for (int i = 0; i < keys.size(); i++) {
				final byte[] key = keys.get(i);
				final byte[] value = values.get(i);
				if (key == null || value == null || tableSchema == null) {
					continue;
				}
				final LogicalFieldCursor cursor = LogicalFieldCursor.open(tableSchema, value);
				final Map<String, byte[]> fieldValues = new LinkedHashMap<>(def.columns().size());
				for (String col : def.columns()) {
					fieldValues.put(col, cursor.indexKeyBytes(cursor.ordinalOf(col)));
				}
				final long keyNativePointer = BPValueHelper.toNativeValueRef(key, null);
				index.putIndexKeyValuesForNamed(def, keyNativePointer, key, fieldValues);
			}
			index.startAnalyze();
		});
	}

	/** Synchronous index upsert from encoded blob (SQL-first / TableSchema path).
	 * Used by local IMDG microbenches and SQL {@link org.genfork.grid.store.TableStore#upsert}
	 * after map put so {@code selectKeys} observes the row.
	 */
	public void indexNow(byte[] key, byte[] value) {
		if (key == null || value == null) {
			return;
		}
		runMutationAndAwait(() -> upsertIndexValuesFromSchemaUnlocked(new AddEntry(null, key, value)));
	}

	/** Synchronous index remove after map delete (SQL path). */
	public void indexNowRemove(byte[] key) {
		if (key == null) {
			return;
		}
		runMutationAndAwait(() -> removeFromIndexUnlocked(new RemoveEntry(key)));
	}

	/**
	 * Backfill a single named index from an encoded row blob (CREATE INDEX).
	 * Does not rebuild unrelated secondary indexes.
	 */
	public void indexNowForIndex(org.genfork.grid.catalog.IndexDef def, byte[] key, byte[] value) {
		if (def == null || key == null || value == null || tableSchema == null) {
			return;
		}
		final LogicalFieldCursor cursor = LogicalFieldCursor.open(tableSchema, value);
		final Map<String, byte[]> fieldValues = new LinkedHashMap<>(def.columns().size());
		for (String col : def.columns()) {
			fieldValues.put(col, cursor.indexKeyBytes(cursor.ordinalOf(col)));
		}
		final long keyNativePointer = BPValueHelper.toNativeValueRef(key, null);
		runMutationAndAwait(() -> index.putIndexKeyValuesForNamed(def, keyNativePointer, key, fieldValues));
	}

	/**
	 * Fast path when heap column values are already known (IMDG put): skip cursor open / field copy.
	 */
	public void indexNowFromValues(byte[] key, Object[] columnValues) {
		if (key == null || columnValues == null || tableSchema == null) {
			return;
		}
		final FieldMetaData[] orderIndexFields = tableSchema.orderIndexFields();
		byte[][] orderFields = null;
		if (orderIndexFields != null && orderIndexFields.length > 0) {
			orderFields = new byte[orderIndexFields.length][];
			for (int i = 0; i < orderIndexFields.length; i++) {
				final int ord = tableSchema.requireColumn(orderIndexFields[i].getName()).ordinal();
				orderFields[i] = SqlWireUtil.toGenericArray(columnValues[ord]);
			}
		}
		final long keyNativePointer = BPValueHelper.toNativeValueRef(key, orderFields);
		final LinkedHashMap<String, byte[]> fieldValues = FIELD_VALUES_SCRATCH.get();
		fieldValues.clear();
		for (List<HashField[]> list : tableSchema.indexHashFields().values()) {
			for (HashField[] hashFields : list) {
				for (HashField hashField : hashFields) {
					final String fieldName = hashField.field().getName();
					if (fieldValues.containsKey(fieldName)) {
						continue;
					}
					final int ord = tableSchema.requireColumn(fieldName).ordinal();
					fieldValues.put(fieldName, SqlWireUtil.toGenericArray(columnValues[ord]));
				}
			}
		}
		runMutationAndAwait(() -> index.putIndexKeyValues(keyNativePointer, key, fieldValues));
	}

	public void start() {
		if (running.compareAndSet(false, true)) {
			ThreadService.getScheduledExecutor().schedule(this, 100, MILLISECONDS);
		}
	}

	public void stop() {
		running.set(false);
	}

	/**
	 * Background drain job (scheduled executor) — OK to run index work off client VT/Netty.
	 * Mutations still go through {@link #mutationQueue} so they never race {@code indexNow*}.
	 */
	@Override
	public void run() {
		try {
			final List<Entry> entries = new ArrayList<>();
			queue.drainTo(entries);

			if (!CollectionUtils.isEmpty(entries)) {
				runMutationAndAwait(() -> {
					for (Entry entry : entries) {
						try {
							if (entry instanceof AddEntry addEntry) {
								upsertIndexValuesFromSchemaUnlocked(addEntry);
							} else if (entry instanceof RemoveEntry removeEntry) {
								removeFromIndexUnlocked(removeEntry);
							}
						} catch (RuntimeException ex) {
							if (ex instanceof NonUniqueValueException) {
								log.warn("STRICT index upsert failed (async drain) keyHash={}: {}",
										entry.getKey() == null ? -1 : java.util.Arrays.hashCode(entry.getKey()),
										ex.toString());
								throw ex;
							}
							throw ex;
						}
					}
					index.startAnalyze();
				});
			}
		} finally {
			if (running.get()) {
				ThreadService.getScheduledExecutor().schedule(this, 10, MILLISECONDS);
			}
		}
	}

	/**
	 * Enqueue BPTree mutation on logic VT mailbox; await completion without a monitor.
	 */
	private void runMutationAndAwait(Runnable mutation) {
		if (Boolean.TRUE.equals(ON_INDEX_MAILBOX.get())) {
			mutation.run();
			return;
		}
		final CompletableFuture<Void> done = new CompletableFuture<>();
		final ExecutorService logic = ThreadService.getLogicExecutor();
		mutationQueue.submit(logic, () -> {
			ON_INDEX_MAILBOX.set(Boolean.TRUE);
			try {
				mutation.run();
				done.complete(null);
			} catch (Throwable t) {
				done.completeExceptionally(t);
			} finally {
				ON_INDEX_MAILBOX.remove();
			}
		});
		try {
			done.join();
		} catch (CompletionException ex) {
			final Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("index mutation failed", cause);
		}
	}

	/**
	 * SQL-first path: index field bytes from blob via {@link LogicalFieldCursor} (no POJO / toValue).
	 * Caller must already be on the index mailbox (or hold {@link #ON_INDEX_MAILBOX}).
	 */
	private void upsertIndexValuesFromSchemaUnlocked(AddEntry entry) {
		final byte[] value = entry.getValue();
		if (value == null) {
			return;
		}

		final byte[] keyArray = entry.getKey();
		final LogicalFieldCursor cursor = LogicalFieldCursor.open(tableSchema, value);

		final FieldMetaData[] orderIndexFields = tableSchema.orderIndexFields();
		byte[][] orderFields = null;
		if (orderIndexFields != null && orderIndexFields.length > 0) {
			orderFields = new byte[orderIndexFields.length][];
			for (int i = 0; i < orderIndexFields.length; i++) {
				final FieldMetaData orderIndexField = orderIndexFields[i];
				orderFields[i] = cursor.indexKeyBytes(cursor.ordinalOf(orderIndexField.getName()));
			}
		}

		final long keyNativePointer = BPValueHelper.toNativeValueRef(keyArray, orderFields);

		final Map<String, byte[]> fieldValues = new LinkedHashMap<>();
		final Map<IndexType, List<HashField[]>> indexFields = tableSchema.indexHashFields();
		for (List<HashField[]> list : indexFields.values()) {
			for (int i = 0; i < list.size(); i++) {
				final HashField[] hashFields = list.get(i);
				for (HashField hashField : hashFields) {
					final FieldMetaData fieldMetaData = hashField.field();
					final String fieldName = fieldMetaData.getName();
					if (fieldValues.containsKey(fieldName)) {
						continue;
					}
					fieldValues.put(fieldName, cursor.indexKeyBytes(cursor.ordinalOf(fieldName)));
				}
			}
		}

		index.putIndexKeyValues(keyNativePointer, keyArray, fieldValues);
	}

	private void removeFromIndexUnlocked(RemoveEntry entry) {
		final byte[] key = entry.getKey();
		index.remove(key);
	}
}
