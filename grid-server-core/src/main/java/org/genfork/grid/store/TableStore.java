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
package org.genfork.grid.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import org.springframework.util.CollectionUtils;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.CheckDef;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.mem.stage.GridEntriesProcessor.AddEntry;
import org.genfork.grid.mem.stage.GridEntriesProcessor.RemoveEntry;
import org.genfork.grid.mem.stage.GridEntriesWorker;
import org.genfork.grid.mem.stage.GridIndexWorker;
import org.genfork.grid.mem.stage.WorkingSetBudget;
import org.genfork.grid.overlay.OverlayStore;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.QueryData;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.query.plan.UpdatePlan;
import org.genfork.grid.query.plan.TableRowStats;
import org.genfork.grid.replication.MutationRecorder;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.ModifyPayload;
import org.genfork.grid.serial.BlobFieldModifier;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.PrimaryKeyCodec;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.serial.UpdateAssignMergeUtil;
import org.genfork.grid.sql.tx.KeyWrapper;
import org.genfork.grid.utils.ArrayUtil;
import org.genfork.grid.utils.SerialUtil;

/**
 * Per-table storage: sharded {@link GridEntriesProcessor} + {@link GridCompositeIndex}.
 * Domain id for replication is the table name (not a Java FQCN).
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public final class TableStore {
	private static final int ANALYZE_HISTOGRAM_BUCKETS = 32;
	private static final String CHECK_SELECT_PREFIX = "SELECT * FROM ";
	private static final String CHECK_WHERE = " WHERE ";
	private volatile TableSchema schema;
	private final int shards;
	private final GridEntriesProcessor[] processors;
	private final GridEntriesWorker[] workers;
	private final GridCompositeIndex index;
	private final GridIndexWorker indexWorker;
	private final ReplicationCoordinator replicationCoordinator;
	private MutationRecorder mutationRecorder;
	private OverlayStore overlayStore;
	private long autoPinTtlMs;
	private final ConcurrentHashMap<GridEntriesProcessor.KeyEntry, Object> fieldModifyLocks =
			new ConcurrentHashMap<>();
	/** Test hook: remaining successful flushTx* ops before injected failure (−1 = off). */
	private volatile int txFlushFailCountdown = -1;
	/** Overlay from SQL {@code ANALYZE} (nullable until analyzed). */
	private volatile TableAnalyzeStats analyzeStatsOverlay;


	public TableStore(TableSchema schema,
	                  int shards,
	                  ReplicationCoordinator replicationCoordinator) {
		this.schema = schema;
		this.shards = Math.max(GridConfigurationProperties.DEFAULT_SHARDS, shards);
		this.replicationCoordinator = replicationCoordinator;

		this.index = new GridCompositeIndex(schema);
		this.indexWorker = new GridIndexWorker(index, schema);
		this.indexWorker.start();

		this.processors = new GridEntriesProcessor[this.shards];
		this.workers = new GridEntriesWorker[this.shards];
		for (int i = 0; i < this.shards; i++) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(
					i, new GridScalableMap(), index, indexWorker);
			processors[i] = processor;
			final GridEntriesWorker worker = new GridEntriesWorker(processor);
			workers[i] = worker;
			worker.start();
		}

		index.setRowResolver(key -> getProcessor(key).get(key));
		index.setTableRowStatsSupplier(this::approxRowStats);

		if (replicationCoordinator != null && replicationCoordinator.isEnabled()) {
			final MutationRecorder recorder = replicationCoordinator.registerDomain(
					schema.tableName(),
					shard -> shard >= 0 && shard < this.shards ? processors[shard] : null,
					true
			);
			if (recorder != null) {
				this.mutationRecorder = recorder;
				for (GridEntriesProcessor processor : processors) {
					processor.setBatchCommitListener(recorder::recordCommittedBatchBlocking);
				}
			}
			final WorkingSetBudget budget = replicationCoordinator.createWorkingSetBudget();
			if (budget != null) {
				budget.setEvictHandler((shard, key) -> {
					if (shard >= 0 && shard < processors.length && processors[shard] != null) {
						processors[shard].evictCommitted(key);
					}
				});
				for (GridEntriesProcessor processor : processors) {
					processor.setWorkingSetBudget(budget);
				}
				// Partial WS / eviction: sealed merge required for cold secondary keys.
				index.setSealedRamAuthoritative(false);
			} else {
				// FULL hydrate, unlimited WS: non-empty RAM index hits skip sealed .sbpt merge.
				index.setSealedRamAuthoritative(true);
			}
		}
	}

	public TableSchema schema() {
		return schema;
	}

	/**
	 * Soft overlay for optional auto-pin on hot write ({@code autoPinTtlMs > 0}).
	 */
	public void setOverlay(OverlayStore overlayStore, long autoPinTtlMs) {
		this.overlayStore = overlayStore;
		this.autoPinTtlMs = autoPinTtlMs;
	}

	public GridCompositeIndex index() {
		return index;
	}

	public void addIndex(IndexDef indexDef, long newEpoch) {
		Objects.requireNonNull(indexDef, "indexDef");
		final TableSchema next = schema.withIndex(indexDef, newEpoch);
		index.addNamedIndex(indexDef, next);
		applySchema(next);
		backfillIndex(indexDef);
	}

	public void dropIndex(String indexName, long newEpoch) {
		final TableSchema next = schema.withoutIndex(indexName, newEpoch);
		index.dropNamedIndex(indexName);
		applySchema(next);
		// Existing trees for remaining indexes keep their entries; dropped tree discarded.
	}

	/**
	 * Apply ALTER TABLE column layout.
	 * <p>
	 * ADD COLUMN (trailing): existing blobs pad as null via {@link LogicalFieldCursor}; secondary
	 * indexes unchanged. DROP COLUMN: rewrite every committed row to the new layout, then rebuild
	 * all secondary indexes so ordinal/trailer drift cannot corrupt query plans.
	 */
	public void replaceColumnSchema(TableSchema next) {
		Objects.requireNonNull(next, "next");
		if (!next.tableName().equalsIgnoreCase(schema.tableName())) {
			throw new IllegalArgumentException("table name mismatch on ALTER");
		}
		final TableSchema previous = this.schema;
		final boolean dropped = next.columnCount() < previous.columnCount();
		final boolean added = next.columnCount() > previous.columnCount();
		if (added) {
			for (ColumnDef col : next.columns()) {
				if (previous.column(col.name()) == null && !col.nullable()) {
					if (hasAnyCommittedRow()) {
						throw new IllegalStateException(
								"ADD COLUMN NOT NULL not supported when table has rows: " + col.name());
					}
				}
			}
		}
		if (dropped) {
			rewriteCommittedRows(previous, next);
		}
		applySchema(next);
		if (dropped && !next.indexes().isEmpty()) {
			rebuildAllSecondaryIndexes();
		} else {
			wireMissingSecondaryIndexes(previous, next);
		}
	}

	/** Wire + backfill indexes that appear on {@code next} but not yet in the live tree map. */
	private void wireMissingSecondaryIndexes(TableSchema previous, TableSchema next) {
		for (IndexDef def : previous.indexes()) {
			if (next.findIndex(def.name()) == null && index.hasNamedIndex(def.name())) {
				index.dropNamedIndex(def.name());
			}
		}
		for (IndexDef def : next.indexes()) {
			if (index.hasNamedIndex(def.name())) {
				continue;
			}
			index.addNamedIndex(def, next);
			backfillIndex(def);
		}
	}

	private boolean hasAnyCommittedRow() {
		ensureAllShardsHydrated();
		for (GridEntriesProcessor processor : processors) {
			if (processor != null && processor.mapSize() > 0) {
				return true;
			}
		}
		return false;
	}

	/** Decode with {@code from}, project onto {@code to} columns, rewrite map bytes (no OpLog). */
	private void rewriteCommittedRows(TableSchema from, TableSchema to) {
		ensureAllShardsHydrated();
		final List<ColumnDef> toCols = to.columns();
		forEachPrimaryKey(key -> {
			final byte[] value = getCommittedBytes(key);
			if (value == null) {
				return;
			}
			final Object[] oldVals = RowEncoder.decode(from, value);
			final Object[] newVals = new Object[toCols.size()];
			for (ColumnDef col : toCols) {
				final ColumnDef old = from.requireColumn(col.name());
				newVals[col.ordinal()] = oldVals[old.ordinal()];
			}
			getProcessor(key).putMapOnly(key, RowEncoder.encode(to, newVals));
		});
	}

	/**
	 * Drop and re-backfill every named secondary index under the current schema.
	 * <p>
	 * Skips the synthetic PRIMARY KEY tree ({@code table_pk}): {@link #forEachPrimaryKey} walks that
	 * tree, so dropping it mid-rebuild would empty the backfill source.
	 */
	private void rebuildAllSecondaryIndexes() {
		final List<IndexDef> defs = new ArrayList<>(schema.indexes());
		for (IndexDef def : defs) {
			if (isSyntheticPrimaryKeyIndex(def)) {
				continue;
			}
			if (index.hasNamedIndex(def.name())) {
				index.dropNamedIndex(def.name());
			}
			index.addNamedIndex(def, schema);
			backfillIndex(def);
		}
	}

	/** Synthetic STRICT PK covering index — never drop during secondary rebuild. */
	private boolean isSyntheticPrimaryKeyIndex(IndexDef def) {
		if (def.kind() != IndexType.STRICT) {
			return false;
		}
		final String syntheticName = schema.tableName() + "_pk";
		if (def.name().equalsIgnoreCase(syntheticName)) {
			return true;
		}
		final List<String> pkNames = new ArrayList<>(schema.pkColumns().size());
		for (ColumnDef col : schema.pkColumns()) {
			pkNames.add(col.name());
		}
		if (def.columns().size() != pkNames.size()) {
			return false;
		}
		for (int i = 0; i < pkNames.size(); i++) {
			if (!def.columns().get(i).equalsIgnoreCase(pkNames.get(i))) {
				return false;
			}
		}
		return true;
	}

	private void applySchema(TableSchema next) {
		this.schema = next;
		index.replaceSchema(next);
		indexWorker.replaceSchema(next);
	}

	private static final int INDEX_BACKFILL_CHUNK = 1_024;

	/** Stream committed rows into the new index only — chunked mailbox, no full map reindex. */
	private void backfillIndex(IndexDef indexDef) {
		final List<byte[]> keys = new ArrayList<>(INDEX_BACKFILL_CHUNK);
		final List<byte[]> values = new ArrayList<>(INDEX_BACKFILL_CHUNK);
		forEachPrimaryKey(key -> {
			final byte[] value = getCommittedBytes(key);
			if (value == null) {
				return;
			}
			keys.add(key);
			values.add(value);
			if (keys.size() >= INDEX_BACKFILL_CHUNK) {
				indexWorker.indexNowForIndexBatch(indexDef, keys, values);
				keys.clear();
				values.clear();
			}
		});
		if (!keys.isEmpty()) {
			indexWorker.indexNowForIndexBatch(indexDef, keys, values);
		}
	}

	public void close() {
		if (index != null) {
			index.cancelDumpStats();
		}
		if (indexWorker != null) {
			indexWorker.stop();
		}
		for (GridEntriesWorker worker : workers) {
			if (worker != null) {
				worker.stop();
			}
		}
	}

	public long upsert(Object[] values) {
		return upsert(values, true);
	}

	/**
	 * Upsert row; when {@code rejectExistingPk} is false, overwrite an existing PK (ON CONFLICT / MERGE).
	 */
	public long upsert(Object[] values, boolean rejectExistingPk) {
		ensureWriterEligible();
		final Object[] coerced = coerceRow(values);
		final byte[] keyBytes = PrimaryKeyCodec.encodeRow(schema, coerced);
		validateConstraints(coerced, keyBytes, rejectExistingPk);
		final byte[] valueBytes = RowEncoder.encode(schema, coerced);
		final int shard = getShard(keyBytes);
		final GridEntriesProcessor processor = processors[shard];
		if (mutationRecorder == null) {
			// Map + sync index only — never stage-queue + indexNow (double-mutates BPTree → CME).
			processor.putMapOnly(keyBytes, valueBytes);
			indexWorker.indexNow(keyBytes, valueBytes);
			maybeAutoPin(keyBytes);
			return 1L;
		}
		mutationRecorder.recordCommittedBlocking(shard, new AddEntry(null, keyBytes, valueBytes));
		maybeAutoPin(keyBytes);
		return 1L;
	}

	/**
	 * Local IMDG put: {@link RowEncoder} + sync map + sync index (no staging queue).
	 * Matches OSS compare microbench semantics (followup map+putIndexKeyValues).
	 * Staged {@link #upsert} remains the durable/replication product path.
	 */
	public long putIndexed(Object... values) {
		ensureWriterEligible();
		final Object[] coerced = coerceRow(values);
		final byte[] keyBytes = PrimaryKeyCodec.encodeRow(schema, coerced);
		final byte[] valueBytes = RowEncoder.encode(schema, coerced);
		final int shard = getShard(keyBytes);
		processors[shard].putMapOnly(keyBytes, valueBytes);
		indexWorker.indexNowFromValues(keyBytes, coerced);
		return 1L;
	}

	/**
	 * Local IMDG delete: map remove + sync index drop (no staging queue).
	 * Used by MATERIALIZED VIEW refresh clear path.
	 */
	public long removeIndexed(Object pkValue) {
		ensureWriterEligible();
		final byte[] keyBytes = PrimaryKeyCodec.encodeArgument(schema, pkValue);
		final int shard = getShard(keyBytes);
		processors[shard].removeMapOnly(keyBytes);
		indexWorker.indexNow(keyBytes, null);
		return 1L;
	}

	/** Index-only key list for SQL (no row decode). Same cost class as legacy GridCompositeIndex.executeStatement. */
	public List<byte[]> selectKeys(String sql) {
		return selectKeys(sql, null);
	}

	/**
	 * Index key list with optional pre-resolved WHERE subquery filters (IN / scalar).
	 */
	public List<byte[]> selectKeys(String sql, List<FilterCondition> resolvedWhereSubqueries) {
		ensureAllShardsHydrated();
		final List<byte[]> keys = index.executeStatement(null, sql, resolvedWhereSubqueries);
		return CollectionUtils.isEmpty(keys) ? List.of() : keys;
	}

	/**
	 * Stream PK keys from a SELECT plan. Visitor returning {@code false} stops early.
	 */
	public void forEachSelectKeys(String sql, Predicate<byte[]> visitor) {
		ensureAllShardsHydrated();
		index.forEachSelectKeys(sql, visitor);
	}

	/**
	 * True when optimized SELECT does not need residual full-table PK scan.
	 */
	public boolean isSelectFullyIndexed(String selectSql) {
		return index.isSelectFullyIndexed(selectSql);
	}

	/**
	 * Maintenance / ANALYZE: stream every primary-key posting (no SQL string, no materialize {@link List}).
	 */
	public void forEachPrimaryKey(Consumer<byte[]> consumer) {
		ensureAllShardsHydrated();
		index.forEachPrimaryKey(consumer);
	}

	/**
	 * Wire EQ on covering index → row PK keys (no SQL string).
	 *
	 * @throws IllegalArgumentException when no covering index exists
	 */
	public List<byte[]> lookupEqKeys(List<String> columns, byte[][] wireValues) {
		ensureAllShardsHydrated();
		return index.lookupEqKeys(columns, wireValues);
	}

	/**
	 * Stream wire EQ hits (no SQL string).
	 */
	public void forEachEqKey(List<String> columns, byte[][] wireValues, Consumer<byte[]> consumer) {
		ensureAllShardsHydrated();
		index.forEachEqKey(columns, wireValues, consumer);
	}

	/**
	 * True when an index covers exact EQ on {@code columns}.
	 */
	public boolean hasEqIndex(List<String> columns) {
		return index.hasEqIndex(columns);
	}

	/** True when a single-column index (BPTree or opt-in BITMAP) exists on {@code columnName}. */
	public boolean hasColumnIndex(String columnName) {
		return index.hasSingleColumnIndex(columnName);
	}

	public GridCompositeIndex compositeIndex() {
		return index;
	}

	/**
	 * Crude map + sealed entry hints for {@link org.genfork.grid.query.plan.QueryCardinality}.
	 * Prefers SQL {@code ANALYZE} sidecar rows when present.
	 */
	public TableRowStats approxRowStats() {
		long mapSize = 0L;
		long sealedHint = 0L;
		for (GridEntriesProcessor processor : processors) {
			if (processor == null) {
				continue;
			}
			mapSize += processor.mapSize();
			sealedHint += processor.sealedEntryHint();
		}
		final TableAnalyzeStats analyzed = analyzeStatsOverlay;
		if (analyzed != null && analyzed.rowCount() > 0L) {
			return TableRowStats.fromAnalyze(analyzed.rowCount(), mapSize, sealedHint);
		}
		return TableRowStats.fromLive(mapSize, sealedHint);
	}

	/**
	 * Run crude {@code ANALYZE}: count committed rows + index fan-out hints.
	 */
	public TableAnalyzeStats analyze() {
		final AtomicLong count = new AtomicLong();
		final Map<String, Map<KeyWrapper, Long>> frequencies = new LinkedHashMap<>();
		for (ColumnDef column : schema.columns()) {
			frequencies.put(column.name(), new LinkedHashMap<>());
		}
		forEachPrimaryKey(key -> {
			final byte[] value = getCommittedBytes(key);
			if (value != null) {
				count.incrementAndGet();
				final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, value);
				for (ColumnDef column : schema.columns()) {
					final byte[] wireValue = cursor.indexKeyBytes(column.ordinal());
					if (wireValue != null) {
						frequencies.get(column.name()).merge(new KeyWrapper(wireValue), 1L, Long::sum);
					}
				}
			}
		});
		final long rows = count.get();
		final Map<String, Long> fanOut = index.approxColumnFanOut(rows);
		final Map<String, List<TableAnalyzeStats.FrequencyBucket>> histograms = new LinkedHashMap<>();
		for (Map.Entry<String, Map<KeyWrapper, Long>> column : frequencies.entrySet()) {
			final List<Map.Entry<KeyWrapper, Long>> sorted = new ArrayList<>(column.getValue().entrySet());
			sorted.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
			final int bucketCount = Math.min(ANALYZE_HISTOGRAM_BUCKETS, sorted.size());
			final List<TableAnalyzeStats.FrequencyBucket> buckets = new ArrayList<>(bucketCount);
			for (int i = 0; i < bucketCount; i++) {
				final Map.Entry<KeyWrapper, Long> bucket = sorted.get(i);
				buckets.add(new TableAnalyzeStats.FrequencyBucket(bucket.getKey().key(), bucket.getValue()));
			}
			histograms.put(column.getKey(), buckets);
		}
		final TableAnalyzeStats stats = new TableAnalyzeStats(rows, fanOut, histograms);
		analyzeStatsOverlay = stats;
		return stats;
	}

	/** Apply previously persisted ANALYZE stats (catalog hydrate). */
	public void setAnalyzeStats(TableAnalyzeStats stats) {
		analyzeStatsOverlay = stats;
	}

	public TableAnalyzeStats analyzeStatsOrNull() {
		return analyzeStatsOverlay;
	}

	/**
	 * Keys matching WHERE without SELECT projection / SqlEngine subquery.
	 * Uses the same index path as {@link #selectKeys}; callers mutate by key bytes.
	 */
	public List<byte[]> keysMatching(String whereSql) {
		Objects.requireNonNull(whereSql, "whereSql");
		final String sql = "SELECT " + schema.pkColumn().name() + " FROM " + schema.tableName()
				+ " WHERE " + whereSql;
		return selectKeys(sql);
	}

	/** Apply mutator per matching key; avoids Object PK round-trip in the engine. */
	public long forEachMatchingKey(String whereSql, ToLongFunction<byte[]> mutator) {
		long affected = 0L;
		for (byte[] key : keysMatching(whereSql)) {
			affected += mutator.applyAsLong(key);
		}
		return affected;
	}

	public long deleteByKeyBytes(byte[] keyBytes) {
		ensureWriterEligible();
		Objects.requireNonNull(keyBytes, "keyBytes");
		final int shard = getShard(keyBytes);
		final GridEntriesProcessor processor = getProcessor(keyBytes);
		if (mutationRecorder == null) {
			processor.removeMapOnly(keyBytes);
			indexWorker.indexNowRemove(keyBytes);
			return 1L;
		}
		mutationRecorder.recordCommittedBlocking(shard,
				new GridEntriesProcessor.RemoveEntry(keyBytes));
		return 1L;
	}

	public long updateSetLiteralsByKey(byte[] keyBytes, Map<String, Object> sets) {
		ensureWriterEligible();
		Objects.requireNonNull(keyBytes, "keyBytes");
		Objects.requireNonNull(sets, "sets");
		final int shard = getShard(keyBytes);
		final GridEntriesProcessor processor = processors[shard];
		final GridEntriesProcessor.KeyEntry keyLock = new GridEntriesProcessor.KeyEntry(keyBytes);
		final Object lock = fieldModifyLocks.computeIfAbsent(keyLock, k -> new Object());
		synchronized (lock) {
			byte[] existing = processor.getCommitted(keyBytes);
			final Object[] values;
			if (existing != null && LogicalFieldCursor.canOpen(schema, existing)) {
				values = RowEncoder.decode(schema, existing);
			} else {
				values = new Object[schema.columnCount()];
				values[schema.pkColumn().ordinal()] =
						SerialUtil.readPrimitives(keyBytes, 0, schema.pkColumn().javaType());
			}
			for (Map.Entry<String, Object> e : sets.entrySet()) {
				final ColumnDef col = schema.requireColumn(e.getKey());
				values[col.ordinal()] = RowEncoder.coerce(col, e.getValue());
			}
			validateConstraints(values, keyBytes, false);
			final byte[] merged = RowEncoder.encode(schema, values);
			if (mutationRecorder == null) {
				processor.installCommitted(keyBytes, merged, false);
				return 1L;
			}
			mutationRecorder.recordCommittedBlocking(shard, new AddEntry(null, keyBytes, merged));
		}
		return 1L;
	}

	public long deleteByPk(Object pkValue) {
		return deleteByKeyBytes(PrimaryKeyCodec.encodeArgument(schema, pkValue));
	}

	public long updateByPlan(UpdatePlan plan) {
		return updateByPlan(plan, Map.of());
	}

	/**
	 * Apply RMW plan plus optional SET literals in one {@link BlobFieldModifier} pass.
	 */
	public long updateByPlan(UpdatePlan plan, Map<String, Object> literals) {
		if (!schema.pkColumn().name().equalsIgnoreCase(plan.pkColumn())) {
			throw new IllegalArgumentException("WHERE must use PK " + schema.pkColumn().name());
		}
		final Object keyObj = RowEncoder.coerce(schema.pkColumn(), plan.pkValue());
		final List<ModifyPayload.Decoded> assigns =
				UpdateAssignMergeUtil.toDecoded(plan, literals, schema);
		return applyFieldModify(keyObj, assigns);
	}

	/**
	 * Install a pre-merged UPSERT blob (one encode → one put; no second rewrite).
	 */
	public long installEncodedUpsert(EncodedRow encoded) {
		ensureWriterEligible();
		Objects.requireNonNull(encoded, "encoded");
		Objects.requireNonNull(encoded.keyBytes(), "keyBytes");
		Objects.requireNonNull(encoded.valueBytes(), "valueBytes");
		validateEncodedChecks(encoded.valueBytes());
		final byte[] keyBytes = encoded.keyBytes();
		final byte[] valueBytes = encoded.valueBytes();
		final int shard = getShard(keyBytes);
		final GridEntriesProcessor processor = processors[shard];
		final GridEntriesProcessor.KeyEntry keyLock = new GridEntriesProcessor.KeyEntry(keyBytes);
		final Object lock = fieldModifyLocks.computeIfAbsent(keyLock, k -> new Object());
		synchronized (lock) {
			if (mutationRecorder == null) {
				processor.installCommitted(keyBytes, valueBytes, false);
				return 1L;
			}
			mutationRecorder.recordCommittedBlocking(shard, new AddEntry(null, keyBytes, valueBytes));
		}
		return 1L;
	}

	public long applyFieldModify(Object keyObj, List<ModifyPayload.Decoded> assigns) {
		ensureWriterEligible();
		final byte[] keyArray = SqlWireUtil.toGenericArray(keyObj);
		final int shard = getShard(keyArray);
		final GridEntriesProcessor processor = processors[shard];
		final GridEntriesProcessor.KeyEntry keyLock = new GridEntriesProcessor.KeyEntry(keyArray);
		final Object lock = fieldModifyLocks.computeIfAbsent(keyLock, k -> new Object());
		final byte[] merged;
		synchronized (lock) {
			byte[] existing = processor.getCommitted(keyArray);
			if (existing == null) {
				final Object[] seed = new Object[schema.columnCount()];
				seed[schema.pkColumn().ordinal()] = keyObj;
				existing = RowEncoder.encode(schema, coerceRow(seed));
			}
			merged = BlobFieldModifier.apply(schema, existing, assigns);
			validateEncodedChecks(merged);
			if (mutationRecorder == null) {
				processor.installCommitted(keyArray, merged, false);
				return 1L;
			}
			mutationRecorder.recordCommittedBlocking(shard, new AddEntry(null, keyArray, merged));
		}
		return 1L;
	}

	public Object[] getByPk(Object pkValue) {
		final byte[] keyBytes = PrimaryKeyCodec.encodeArgument(schema, pkValue);
		final byte[] value = getProcessor(keyBytes).getCommitted(keyBytes);
		if (value == null) {
			return null;
		}
		return LogicalFieldCursor.open(schema, value).project(null);
	}

	public List<Object[]> select(String sql, List<String> projection) {
		final List<byte[]> keys = selectKeys(sql);
		if (keys.isEmpty()) {
			return List.of();
		}
		final List<Object[]> rows = new ArrayList<>(keys.size());
		final int[] projOrds = resolveProjection(projection);
		for (byte[] keyArray : keys) {
			final byte[] value = getProcessor(keyArray).get(keyArray);
			if (value == null) {
				continue;
			}
			rows.add(LogicalFieldCursor.open(schema, value).project(projOrds));
		}
		return rows;
	}

	/** Full committed scan without SQL (JOIN nested-loop / diagnostics) — PK stream + get. */
	public List<Object[]> allRows() {
		ensureAllShardsHydrated();
		final List<Object[]> rows = new ArrayList<>();
		forEachPrimaryKey(key -> {
			final byte[] value = getCommittedBytes(key);
			if (value != null) {
				rows.add(LogicalFieldCursor.open(schema, value).project(null));
			}
		});
		return rows;
	}

	/**
	 * Visit every committed key/value pair as raw {@code byte[]} (no row map decode).
	 */
	public void forEachCommitted(BiConsumer<byte[], byte[]> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		ensureAllShardsHydrated();
		for (GridEntriesProcessor processor : processors) {
			processor.forEachCommitted(consumer);
		}
	}

	private int[] resolveProjection(List<String> projection) {
		if (projection == null || projection.isEmpty()
				|| (projection.size() == 1 && "*".equals(projection.getFirst()))) {
			return null;
		}
		final int[] ords = new int[projection.size()];
		for (int i = 0; i < projection.size(); i++) {
			ords[i] = schema.requireColumn(projection.get(i)).ordinal();
		}
		return ords;
	}

	private Object[] coerceRow(Object[] values) {
		if (values.length != schema.columnCount()) {
			throw new IllegalArgumentException(
					"INSERT expects " + schema.columnCount() + " values, got " + values.length);
		}
		final Object[] out = new Object[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = RowEncoder.coerce(schema.columns().get(i), values[i]);
		}
		return out;
	}

	/**
	 * Fail-closed NOT NULL (via {@link RowEncoder#coerce}) + UNIQUE/STRICT + duplicate PK on insert.
	 */
	public void validateConstraints(Object[] coerced, byte[] keyBytes, boolean rejectExistingPk) {
		Objects.requireNonNull(coerced, "coerced");
		Objects.requireNonNull(keyBytes, "keyBytes");
		if (rejectExistingPk && getCommittedBytes(keyBytes) != null) {
			throw new NonUniqueValueException(
					"duplicate primary key for table " + schema.tableName());
		}
		validateChecks(coerced);
		final String pkName = schema.pkColumn().name();
		for (IndexDef idx : schema.indexes()) {
			if (idx.kind() != IndexType.STRICT) {
				continue;
			}
			if (idx.columns().size() == 1 && idx.columns().getFirst().equalsIgnoreCase(pkName)) {
				continue;
			}
			boolean anyNull = false;
			for (String colName : idx.columns()) {
				final ColumnDef col = schema.requireColumn(colName);
				if (coerced[col.ordinal()] == null) {
					anyNull = true;
					break;
				}
			}
			if (anyNull) {
				continue;
			}
			for (Object[] existing : allRows()) {
				boolean same = true;
				for (String colName : idx.columns()) {
					final ColumnDef col = schema.requireColumn(colName);
					final Object left = coerced[col.ordinal()];
					final Object right = existing[col.ordinal()];
					if (left == null ? right != null : !left.equals(right)) {
						same = false;
						break;
					}
				}
				if (!same) {
					continue;
				}
				boolean samePrimaryKey = true;
				for (ColumnDef pkColumn : schema.pkColumns()) {
					final Object existingPk = existing[pkColumn.ordinal()];
					final Object newPk = coerced[pkColumn.ordinal()];
					if (existingPk == null ? newPk != null : !existingPk.equals(newPk)) {
						samePrimaryKey = false;
						break;
					}
				}
				if (!samePrimaryKey) {
					throw new NonUniqueValueException(
							"duplicate value for unique index " + idx.name());
				}
			}
		}
	}

	private void validateChecks(Object[] coerced) {
		if (schema.checks().isEmpty()) {
			return;
		}
		validateEncodedChecks(RowEncoder.encode(schema, coerced));
	}

	private void validateEncodedChecks(byte[] valueBytes) {
		for (CheckDef check : schema.checks()) {
			final String sql = CHECK_SELECT_PREFIX + schema.tableName() + CHECK_WHERE + check.expressionSql();
			final QueryData query = QueryParser.parseAndBuildCondition(null, sql, Map.of());
			final FilterCondition condition = query.filter() == null ? null : query.filter().conditionTree();
			if (condition != null && !condition.matches(valueBytes, schema)) {
				throw new IllegalStateException("CHECK violation: " + check.name());
			}
		}
	}

	/** Build full row from named columns (missing → null). */
	public Object[] rowFromNamed(Map<String, Object> named) {
		final Object[] out = new Object[schema.columnCount()];
		for (ColumnDef col : schema.columns()) {
			Object v = null;
			for (Map.Entry<String, Object> e : named.entrySet()) {
				if (e.getKey().equalsIgnoreCase(col.name())) {
					v = e.getValue();
					break;
				}
			}
			out[col.ordinal()] = RowEncoder.coerce(col, v);
		}
		return out;
	}

	private GridEntriesProcessor getProcessor(byte[] key) {
		final int shard = getShard(key);
		if (replicationCoordinator != null) {
			replicationCoordinator.ensureShardHydrated(schema.tableName(), shard);
		}
		return processors[shard];
	}

	private void ensureAllShardsHydrated() {
		if (replicationCoordinator == null || !replicationCoordinator.isLazyHydrate()) {
			return;
		}
		for (int shard = 0; shard < shards; shard++) {
			replicationCoordinator.ensureShardHydrated(schema.tableName(), shard);
		}
	}

	private int getShard(byte[] key) {
		return Math.abs(ArrayUtil.fastHash(key) % shards);
	}

	public int shardOf(byte[] keyBytes) {
		return getShard(keyBytes);
	}

	/** Domain shard count for this table (MapReduce partition width). */
	public int shardCount() {
		return shards;
	}

	/** Encode upsert row without writing (SQL TX dirty path). */
	public EncodedRow encodeUpsert(Object[] values) {
		return encodeUpsert(values, true);
	}

	public EncodedRow encodeUpsert(Object[] values, boolean rejectExistingPk) {
		final Object[] coerced = coerceRow(values);
		final byte[] keyBytes = PrimaryKeyCodec.encodeRow(schema, coerced);
		validateConstraints(coerced, keyBytes, rejectExistingPk);
		final byte[] valueBytes = RowEncoder.encode(schema, coerced);
		return new EncodedRow(keyBytes, valueBytes, getShard(keyBytes));
	}

	public EncodedRow encodeDeletePk(Object pkValue) {
		final byte[] keyBytes = PrimaryKeyCodec.encodeArgument(schema, pkValue);
		return new EncodedRow(keyBytes, null, getShard(keyBytes));
	}

	public byte[] keyBytesForPk(Object pkValue) {
		return PrimaryKeyCodec.encodeArgument(schema, pkValue);
	}

	public byte[] getCommittedBytes(byte[] keyBytes) {
		return getProcessor(keyBytes).getCommitted(keyBytes);
	}

	/**
	 * Encode UPDATE SET literals against committed or provided base bytes (TX dirty overlay).
	 */
	public EncodedRow encodeSetLiterals(byte[] keyBytes, byte[] baseOrNull, Map<String, Object> sets) {
		final Object[] values;
		if (baseOrNull != null && LogicalFieldCursor.canOpen(schema, baseOrNull)) {
			values = RowEncoder.decode(schema, baseOrNull);
		} else {
			values = new Object[schema.columnCount()];
			values[schema.pkColumn().ordinal()] =
					SerialUtil.readPrimitives(keyBytes, 0, schema.pkColumn().javaType());
		}
		for (Map.Entry<String, Object> e : sets.entrySet()) {
			final ColumnDef col = schema.requireColumn(e.getKey());
			values[col.ordinal()] = RowEncoder.coerce(col, e.getValue());
		}
		validateConstraints(values, keyBytes, false);
		final byte[] merged = RowEncoder.encode(schema, values);
		return new EncodedRow(keyBytes, merged, getShard(keyBytes));
	}

	/** Encode RMW UpdatePlan against base bytes (or seed missing row). */
	public EncodedRow encodeUpdatePlan(UpdatePlan plan, byte[] baseOrNull) {
		return encodeUpdatePlan(plan, baseOrNull, Map.of());
	}

	/**
	 * Encode RMW plan plus optional SET literals in one {@link BlobFieldModifier} pass.
	 */
	public EncodedRow encodeUpdatePlan(
			UpdatePlan plan,
			byte[] baseOrNull,
			Map<String, Object> literals
	) {
		if (!schema.pkColumn().name().equalsIgnoreCase(plan.pkColumn())) {
			throw new IllegalArgumentException("WHERE must use PK " + schema.pkColumn().name());
		}
		final Object keyObj = RowEncoder.coerce(schema.pkColumn(), plan.pkValue());
		final byte[] keyArray = SqlWireUtil.toGenericArray(keyObj);
		final List<ModifyPayload.Decoded> assigns =
				UpdateAssignMergeUtil.toDecoded(plan, literals, schema);
		byte[] existing = baseOrNull;
		if (existing == null) {
			final Object[] seed = new Object[schema.columnCount()];
			seed[schema.pkColumn().ordinal()] = keyObj;
			existing = RowEncoder.encode(schema, coerceRow(seed));
		}
		final byte[] merged = BlobFieldModifier.apply(schema, existing, assigns);
		validateEncodedChecks(merged);
		return new EncodedRow(keyArray, merged, getShard(keyArray));
	}

	public Object[] projectBytes(byte[] valueBytes, List<String> projection) {
		return LogicalFieldCursor.open(schema, valueBytes).project(resolveProjection(projection));
	}

	/**
	 * TX commit flush: UPSERT via ORCHID/MutationRecorder when replication is on
	 * (ReplicaApplier stages until TX_COMMIT), else sync map+index with undo snapshot.
	 * Call only from {@code SqlTxCommitter} after TX_BEGIN markers when replicated.
	 */
	public PriorBytes flushTxUpsert(byte[] keyBytes, byte[] valueBytes) {
		ensureWriterEligible();
		Objects.requireNonNull(keyBytes, "keyBytes");
		validateEncodedChecks(valueBytes);
		maybeInjectFlushFail();
		final int shard = getShard(keyBytes);
		final GridEntriesProcessor processor = processors[shard];
		final byte[] prior = processor.getCommitted(keyBytes);
		if (mutationRecorder == null) {
			if (prior != null) {
				index.remove(keyBytes);
			}
			processor.putMapOnly(keyBytes, valueBytes);
			indexWorker.indexNow(keyBytes, valueBytes);
			maybeAutoPin(keyBytes);
			return new PriorBytes(keyBytes, prior);
		}
		mutationRecorder.recordCommittedBlocking(shard, new AddEntry(null, keyBytes, valueBytes));
		maybeAutoPin(keyBytes);
		return new PriorBytes(keyBytes, prior);
	}

	/**
	 * TX commit flush: DELETE via ORCHID/MutationRecorder when replication is on,
	 * else sync map+index remove with undo snapshot.
	 */
	public PriorBytes flushTxDelete(byte[] keyBytes) {
		ensureWriterEligible();
		Objects.requireNonNull(keyBytes, "keyBytes");
		maybeInjectFlushFail();
		final int shard = getShard(keyBytes);
		final GridEntriesProcessor processor = processors[shard];
		final byte[] prior = processor.getCommitted(keyBytes);
		if (mutationRecorder == null) {
			processor.installCommitted(keyBytes, null, true);
			return new PriorBytes(keyBytes, prior);
		}
		mutationRecorder.recordCommittedBlocking(shard, new RemoveEntry(keyBytes));
		return new PriorBytes(keyBytes, prior);
	}

	/**
	 * Batch TX flush for one shard stream: pipelined ORCHID + single OpLog group fsync when
	 * replication is on; otherwise per-key local install with undo snapshots.
	 */
	public List<PriorBytes> flushTxBatch(List<TxFlushOp> ops) {
		ensureWriterEligible();
		Objects.requireNonNull(ops, "ops");
		if (ops.isEmpty()) {
			return List.of();
		}
		maybeInjectFlushFail();
		if (mutationRecorder == null) {
			final List<PriorBytes> priors = new ArrayList<>(ops.size());
			for (TxFlushOp op : ops) {
				Objects.requireNonNull(op, "op");
				Objects.requireNonNull(op.keyBytes(), "keyBytes");
				if (op.delete()) {
					priors.add(flushTxDelete(op.keyBytes()));
				} else {
					priors.add(flushTxUpsert(op.keyBytes(), op.valueBytesOrNull()));
				}
			}
			return priors;
		}
		final TableStoreFlushSupport.PreparedBatch prepared = TableStoreFlushSupport.prepareRecorderBatch(
				ops, this::getShard, shard -> processors[shard], this::validateEncodedChecks, false);
		mutationRecorder.recordCommittedBatchBlocking(prepared.shard(), prepared.entries());
		for (TxFlushOp op : ops) {
			if (!op.delete()) {
				maybeAutoPin(op.keyBytes());
			}
		}
		return prepared.priors();
	}

	/**
	 * Single-stream TX unit: multi-op → {@code TX_BEGIN}+data+{@code TX_COMMIT} with one OpLog
	 * groupForce; single-op autocommit (no envelope) → data-only path (no markers).
	 */
	public List<PriorBytes> flushTxUnit(long txId, byte[] beginValue, List<TxFlushOp> ops) {
		ensureWriterEligible();
		Objects.requireNonNull(ops, "ops");
		if (ops.isEmpty()) {
			return List.of();
		}
		maybeInjectFlushFail();
		if (mutationRecorder == null) {
			return flushTxBatch(ops);
		}
		final TableStoreFlushSupport.PreparedBatch prepared = TableStoreFlushSupport.prepareRecorderBatch(
				ops, this::getShard, shard -> processors[shard], this::validateEncodedChecks, true);
		mutationRecorder.recordTxUnitBlocking(prepared.shard(), txId, beginValue, prepared.entries());
		for (TxFlushOp op : ops) {
			if (!op.delete()) {
				maybeAutoPin(op.keyBytes());
			}
		}
		return prepared.priors();
	}

	/** One key mutation for {@link #flushTxBatch}. */
	public record TxFlushOp(byte[] keyBytes, byte[] valueBytesOrNull, boolean delete) {
	}

	/** Restore map+index to bytes captured before a mid-flush install (no-repl unit undo). */
	public void restorePrior(PriorBytes prior) {
		Objects.requireNonNull(prior, "prior");
		final GridEntriesProcessor processor = getProcessor(prior.keyBytes());
		if (prior.priorOrNull() == null) {
			processor.installCommitted(prior.keyBytes(), null, true);
		} else {
			index.remove(prior.keyBytes());
			processor.putMapOnly(prior.keyBytes(), prior.priorOrNull());
			indexWorker.indexNow(prior.keyBytes(), prior.priorOrNull());
		}
	}

	/**
	 * Test hook: fail after {@code n} successful flushTx* calls (0 = fail on first).
	 * Negative disables. Consumed on trip.
	 */
	public void armTxFlushFailAfter(int successfulOpsBeforeFail) {
		txFlushFailCountdown = successfulOpsBeforeFail;
	}

	/** Thin auto-pin helper — single OverlayStore path, no swarm duplication. */
	private void maybeAutoPin(byte[] keyBytes) {
		if (overlayStore == null) {
			return;
		}
		overlayStore.autoPinOnHotWrite(schema.tableName(), keyBytes, autoPinTtlMs);
	}

	private void maybeInjectFlushFail() {
		final int left = txFlushFailCountdown;
		if (left < 0) {
			return;
		}
		if (left == 0) {
			txFlushFailCountdown = -1;
			throw new IllegalStateException("injected mid-flush fail");
		}
		txFlushFailCountdown = left - 1;
	}

	/** Snapshot of committed bytes before a TX flush install (for undo). */
	public record PriorBytes(byte[] keyBytes, byte[] priorOrNull) {
	}

	public record EncodedRow(byte[] keyBytes, byte[] valueBytes, int shard) {
	}

	private void ensureWriterEligible() {
		ReplicaAccessGate.ensureWrite(replicationCoordinator);
	}

	/** Convenience for lit SET assigns not covered by RMW UpdatePlan forms. */
	public long updateSetLiterals(String pkColumn, Object pkValue, Map<String, Object> sets) {
		if (!schema.pkColumn().name().equalsIgnoreCase(pkColumn)) {
			throw new IllegalArgumentException("WHERE must use PK " + schema.pkColumn().name());
		}
		final Object keyObj = RowEncoder.coerce(schema.pkColumn(), pkValue);
		final byte[] keyArray = SqlWireUtil.toGenericArray(keyObj);
		return updateSetLiteralsByKey(keyArray, sets);
	}
}
