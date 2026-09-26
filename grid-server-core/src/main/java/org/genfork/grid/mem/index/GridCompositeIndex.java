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
package org.genfork.grid.mem.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.util.CollectionUtils;
import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.exceptions.ConditionValidationException;
import org.genfork.grid.mem.ByteArrayContainer;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.AbstractBPTree;
import org.genfork.grid.mem.index.btree.CompositeTreeKey;
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.GridPointerCompositeBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.mem.index.btree.TreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AlwaysFalseCondition;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.CompositeIndexCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.filters.impl.NotCondition;
import org.genfork.grid.query.filters.impl.OrCondition;
import org.genfork.grid.query.plan.AggregateSpec;
import org.genfork.grid.query.filters.PkIndexScanUtil;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.query.plan.FilterConditionData;
import org.genfork.grid.query.plan.JoinSpec;
import org.genfork.grid.query.plan.PagingData;
import org.genfork.grid.query.plan.QueryData;
import org.genfork.grid.query.plan.QueryOptimizer;
import org.genfork.grid.query.plan.QueryCardinality;
import org.genfork.grid.query.plan.QueryPagingContext;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.query.plan.SortOrderData;
import org.genfork.grid.query.plan.TableRowStats;
import org.genfork.grid.query.plan.strategy.TableScanStrategy;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackBitmap;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackCompositeIndex;
import org.genfork.grid.replication.snapshot.sealed.SealedFallbackIndex;
import org.genfork.grid.serial.FieldMetaData;
import org.genfork.grid.serial.HashField;
import org.genfork.grid.serial.WireFieldBytes;
import org.genfork.grid.serial.WireFieldCompare;
import org.genfork.grid.threading.ThreadService;

/**
 * Composite secondary index facade: single/composite BPTree (+ optional bitmap) over table columns.
 * <p>
 * RAM trees are a working-set accelerator; sealed {@code .sbpt}/{@code .sbm} remain durable
 * beside the map when durability is enabled.
 *
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class GridCompositeIndex {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GridCompositeIndex.class);
	/**
	 * Initial capacity hint for opt-in {@link GridBitmapIndex}.
	 */
	private static final int BITMAP_INDEX_INITIAL_CAPACITY = 1 << 20;
	private final Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> property2Index = new ConcurrentHashMap<>();
	private final Map<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> compositeIndexes = new ConcurrentHashMap<>();
	private final Map<String, Map<ByteArrayContainer, RevertSingle>> revertProperty2Index = new ConcurrentHashMap<>();
	private final Map<List<String>, Map<ByteArrayContainer, RevertComposite>> revertCompositeProperty2Index = new ConcurrentHashMap<>();


	/**
	 * Prior single-column posting for a PK (tree key + pointer) — used to replace LAX/STRICT upserts.
	 */
	private record RevertSingle(SingleTreeKey treeKey, IndexPointerRef pointer) {
	}


	/**
	 * Prior composite posting for a PK (tree key + pointer).
	 */
	private record RevertComposite(CompositeTreeKey treeKey, IndexPointerRef pointer) {
	}

	private volatile List<String> primaryKeyColumns;
	private final String tableLabel;
	/**
	 * Lowercase column name → catalog {@link FieldMetaData} (SQL-first; no domain Class).
	 */
	private final Map<String, FieldMetaData> fieldsByName;
	/**
	 * Live catalog schema for filter validate (updated on ALTER via {@link #replaceSchema}).
	 */
	private volatile TableSchema schema;
	private Map<String, ArrayIndexType> cachedOrderIndexFields;
	/**
	 * SQL → composite-optimized plan bound to this index instance (invalidated on {@link #clear()}).
	 */
	private final Map<String, QueryData> optimizedPlanCache = new ConcurrentHashMap<>();
	/**
	 * Optional key → valueBytes resolver for residual (unindexed) filter matches.
	 */
	private Function<byte[], byte[]> rowResolver;
	/**
	 * Optional map/sealed size hints for {@link QueryOptimizer} cost model.
	 */
	private Supplier<TableRowStats> tableRowStatsSupplier;
	/**
	 * Catalog index name → def (secondary indexes for CREATE/DROP INDEX).
	 */
	private final Map<String, IndexDef> namedIndexes = new ConcurrentHashMap<>();
	/**
	 * Periodic dumpStats task — cancelled on {@link #clear()} / store close so JMH forks can exit.
	 */
	private volatile ScheduledFuture<?> dumpStatsFuture;

	public GridCompositeIndex(TableSchema schema) {
		this.schema = Objects.requireNonNull(schema, "schema");
		this.tableLabel = schema.tableName();
		this.primaryKeyColumns = pkColumnNamesOf(schema);
		final Map<String, FieldMetaData> byName = new HashMap<>(schema.columnCount() * 2);
		for (FieldMetaData fm : schema.fieldMetas()) {
			byName.put(fm.getName().toLowerCase(Locale.ROOT), fm);
		}
		this.fieldsByName = Collections.unmodifiableMap(byName);
		initOrderIndexFields(schema.orderIndexFields());
		wireIndexes(schema.indexHashFields());
		for (IndexDef idx : schema.indexes()) {
			namedIndexes.put(idx.name().toLowerCase(Locale.ROOT), idx);
		}
		scheduleDumpStats();
	}

	/**
	 * Update catalog schema reference after ALTER (filter validate only; trees rebuilt separately).
	 */
	public void replaceSchema(TableSchema next) {
		this.schema = Objects.requireNonNull(next, "next");
		this.primaryKeyColumns = pkColumnNamesOf(next);
	}

	/**
	 * Ordered PRIMARY KEY column names for exact PK tree bind.
	 */
	public List<String> primaryKeyColumns() {
		return primaryKeyColumns;
	}

	private static List<String> pkColumnNamesOf(TableSchema schema) {
		final List<ColumnDef> pk = schema.pkColumns();
		final List<String> names = new ArrayList<>(pk.size());
		for (ColumnDef col : pk) {
			names.add(col.name());
		}
		return List.copyOf(names);
	}

	/**
	 * Wire a secondary index at runtime (CREATE INDEX) without rebuilding unrelated trees.
	 */
	public void addNamedIndex(IndexDef def, TableSchema schema) {
		Objects.requireNonNull(def, "def");
		Objects.requireNonNull(schema, "schema");
		final String key = def.name().toLowerCase(Locale.ROOT);
		if (namedIndexes.containsKey(key)) {
			throw new IllegalStateException("Index already exists: " + def.name());
		}
		final Map<IndexType, List<HashField[]>> one = new EnumMap<>(IndexType.class);
		final HashField[] fields = new HashField[def.columns().size()];
		for (int i = 0; i < def.columns().size(); i++) {
			final ColumnDef col = schema.requireColumn(def.columns().get(i));
			fields[i] = new HashField(col.nameHash(), schema.fieldMetas()[col.ordinal()]);
		}
		one.put(def.kind(), Collections.singletonList(fields));
		wireIndexes(one);
		namedIndexes.put(key, def);
		optimizedPlanCache.clear();
		QueryParser.clearPlanCache();
	}

	/**
	 * Remove a secondary index by catalog name (DROP INDEX).
	 */
	public void dropNamedIndex(String indexName) {
		if (indexName == null || indexName.isBlank()) {
			throw new IllegalArgumentException("index name required");
		}
		final IndexDef def = namedIndexes.remove(indexName.toLowerCase(Locale.ROOT));
		if (def == null) {
			throw new IllegalStateException("Index not found: " + indexName);
		}
		if (def.columns().size() == 1) {
			final String fieldName = def.columns().getFirst();
			property2Index.remove(fieldName);
			revertProperty2Index.remove(fieldName);
		} else {
			final List<String> combination = new ArrayList<>(def.columns());
			compositeIndexes.remove(combination);
			revertCompositeProperty2Index.remove(combination);
		}
		optimizedPlanCache.clear();
		QueryParser.clearPlanCache();
	}

	public boolean hasSingleColumnIndex(String columnName) {
		return resolveSingleColumnIndex(columnName) != null;
	}

	public boolean hasNamedIndex(String indexName) {
		return indexName != null && namedIndexes.containsKey(indexName.toLowerCase(Locale.ROOT));
	}

	/**
	 * Unbounded page size for {@link IndexPointerRef#pageKeys} (all matching pointers).
	 */
	private static final int UNBOUNDED_KEY_PAGE = Integer.MAX_VALUE;
	/**
	 * Message when FOR UPDATE / probe path would fall back to residual full PK scan.
	 */
	public static final String MSG_WHERE_REQUIRES_USABLE_INDEX = "WHERE requires a usable index (no residual full-table scan)";
	/**
	 * Message when wire EQ columns are not covered by any single/composite index.
	 */
	public static final String MSG_EQ_REQUIRES_INDEX = "Indexed EQ requires an index covering the probe columns";

	/**
	 * Resolve single-column index operation (case-insensitive column name).
	 */
	public AbstractIndexOperation<byte[], SingleTreeKey> resolveSingleColumnIndex(String columnName) {
		if (columnName == null || columnName.isBlank()) {
			return null;
		}
		final AbstractIndexOperation<byte[], SingleTreeKey> exact = property2Index.get(columnName);
		if (exact != null) {
			return exact;
		}
		for (Entry<String, AbstractIndexOperation<byte[], SingleTreeKey>> entry : property2Index.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(columnName)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Resolve composite index whose columns match {@code columns} order-insensitively by name.
	 */
	public AbstractIndexOperation<byte[][], CompositeTreeKey> resolveCompositeIndex(List<String> columns) {
		if (columns == null || columns.isEmpty()) {
			return null;
		}
		final AbstractIndexOperation<byte[][], CompositeTreeKey> exact = compositeIndexes.get(columns);
		if (exact != null) {
			return exact;
		}
		for (Entry<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> entry : compositeIndexes.entrySet()) {
			if (CompositeIndexProbeOps.columnsEqualIgnoreCaseOrdered(entry.getKey(), columns)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Wire EQ probe on indexed columns → row PK byte arrays (no SQL string).
	 * <p>
	 * Single column → {@code property2Index}; multi → exact composite covering those columns.
	 *
	 * @throws IllegalArgumentException when no covering index exists
	 */
	public List<byte[]> lookupEqKeys(List<String> columns, byte[][] wireValues) {
		final IndexOperationResult result = lookupEqResult(columns, wireValues);
		result.expandBitmapPointers();
		final Set<IndexPointerRef> pointers = result.getPointers();
		if (pointers == null || pointers.isEmpty()) {
			return List.of();
		}
		return IndexPointerRef.pageKeys(pointers, 0, UNBOUNDED_KEY_PAGE);
	}

	/**
	 * Stream row PK bytes for an indexed wire EQ (no intermediate full {@link List} when caller stops early).
	 */
	public void forEachEqKey(List<String> columns, byte[][] wireValues, Consumer<byte[]> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		final IndexOperationResult result = lookupEqResult(columns, wireValues);
		result.expandBitmapPointers();
		final Set<IndexPointerRef> pointers = result.getPointers();
		if (pointers == null || pointers.isEmpty()) {
			return;
		}
		for (IndexPointerRef ptr : pointers) {
			final byte[] key = ptr.resolveKey();
			if (key != null) {
				consumer.accept(key);
			}
		}
	}

	private IndexOperationResult lookupEqResult(List<String> columns, byte[][] wireValues) {
		Objects.requireNonNull(columns, "columns");
		Objects.requireNonNull(wireValues, "wireValues");
		if (columns.size() != wireValues.length) {
			throw new IllegalArgumentException("columns/wireValues arity mismatch");
		}
		if (columns.isEmpty()) {
			throw new IllegalArgumentException("columns empty");
		}
		if (columns.size() == 1) {
			final AbstractIndexOperation<byte[], SingleTreeKey> tree = resolveSingleColumnIndex(columns.getFirst());
			if (tree == null) {
				throw new IllegalArgumentException(MSG_EQ_REQUIRES_INDEX + ": " + columns);
			}
			final IndexOperationResult found = tree.searchEq(new SingleTreeKey(wireValues[0]));
			return found == null ? IndexOperationResult.EMPTY : found;
		}
		final AbstractIndexOperation<byte[][], CompositeTreeKey> tree = resolveCompositeIndex(columns);
		if (tree == null) {
			throw new IllegalArgumentException(MSG_EQ_REQUIRES_INDEX + ": " + columns);
		}
		final IndexOperationResult found = tree.searchEq(new CompositeTreeKey(wireValues));
		return found == null ? IndexOperationResult.EMPTY : found;
	}

	/**
	 * True when a covering index exists for exact EQ on {@code columns}.
	 */
	public boolean hasEqIndex(List<String> columns) {
		if (columns == null || columns.isEmpty()) {
			return false;
		}
		if (columns.size() == 1) {
			return resolveSingleColumnIndex(columns.getFirst()) != null;
		}
		return resolveCompositeIndex(columns) != null;
	}

	/**
	 * Stream every primary-key posting (maintenance / ANALYZE / backfill). Prefer EQ probes on hot paths.
	 * <p>
	 * Leaf walk — no full {@code searchAll} HashSet materialization.
	 */
	public void forEachPrimaryKey(Consumer<byte[]> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		final boolean found = PkIndexScanUtil.forEachPrimaryKeyRow(
				property2Index, compositeIndexes, primaryKeyColumns, consumer);
		if (!found) {
			throw new IllegalStateException("PRIMARY KEY index missing for " + tableLabel);
		}
	}

	/**
	 * Resumable PRIMARY KEY leaf cursor for Portal FETCH pull (AlwaysTrue SELECT *).
	 */
	public AbstractBPTree.RowKeyCursor<?, ?> openPrimaryKeyRowCursor() {
		final AbstractBPTree.RowKeyCursor<?, ?> cursor = PkIndexScanUtil.openPrimaryKeyRowCursor(
				property2Index, compositeIndexes, primaryKeyColumns);
		if (cursor == null) {
			throw new IllegalStateException("PRIMARY KEY index missing for " + tableLabel);
		}
		return cursor;
	}

	/**
	 * True when SELECT is AlwaysTrue (no WHERE) with no ORDER BY — Portal pull-cursor eligible.
	 */
	public boolean isRamPrimaryKeyBptree() {
		return PkIndexScanUtil.hasRamPrimaryKeyBptree(property2Index, compositeIndexes, primaryKeyColumns);
	}

	public boolean isAlwaysTrueNoOrderSelect(String selectSql) {
		if (selectSql == null || selectSql.isBlank()) {
			return false;
		}
		final QueryData optimized = optimizedPlanCache.computeIfAbsent(selectSql.trim(), this::buildOptimizedQuery);
		if (optimized.filter() == null || optimized.filter().conditionTree() == null) {
			return false;
		}
		if (!(optimized.filter().conditionTree() instanceof AlwaysTrueCondition)) {
			return false;
		}
		return optimized.sortOrder() == null || optimized.sortOrder().length == 0;
	}

	/**
	 * True when optimized filter needs no residual row match (every leaf is index-backed).
	 * {@link AlwaysTrueCondition} (no WHERE) is fully indexed via PK {@code searchAll}.
	 */
	public boolean isFullyIndexed(FilterCondition condition) {
		return !needsResidualMatch(condition);
	}

	/**
	 * Parse + optimize {@code selectSql}; true when the plan does not require residual full PK scan.
	 */
	public boolean isSelectFullyIndexed(String selectSql) {
		if (selectSql == null || selectSql.isBlank()) {
			return false;
		}
		final QueryData optimized = buildOptimizedQuery(selectSql.trim());
		if (optimized.filter() == null || optimized.filter().conditionTree() == null) {
			return false;
		}
		return isFullyIndexed(optimized.filter().conditionTree());
	}

	/**
	 * Stream PK keys for a SELECT (same planner path as {@link #executeStatement}).
	 * No ORDER BY, or composite leaf order matching ORDER BY: iterates pointers without an
	 * intermediate key {@link List}. Unordered ORDER BY materializes via {@link #executeStatement}.
	 * Visitor returning {@code false} stops enumeration (LIMIT / early-stop).
	 * {@link AlwaysTrueCondition} uses PK leaf walk (no {@code searchAll} HashSet).
	 */
	public void forEachSelectKeys(String selectSql, Predicate<byte[]> visitor) {
		Objects.requireNonNull(visitor, "visitor");
		if (selectSql == null || selectSql.isBlank()) {
			return;
		}
		final QueryData optimizedQuery = optimizedPlanCache.computeIfAbsent(selectSql.trim(), this::buildOptimizedQuery);
		final FilterConditionData conditionData = optimizedQuery.filter();
		if (conditionData == null) {
			return;
		}
		if (optimizedQuery.paging() != null && optimizedQuery.paging().limit() == 0) {
			return;
		}
		if (optimizedQuery.isAggregate() || optimizedQuery.isJoin()) {
			for (byte[] key : executeStatement(null, selectSql)) {
				if (!visitor.test(key)) {
					return;
				}
			}
			return;
		}
		final boolean hasSort = optimizedQuery.sortOrder() != null && optimizedQuery.sortOrder().length > 0;
		final boolean orderedLeafScan = conditionData.useSameOrderAscendingIndex();
		// Unordered ORDER BY needs FilterThenSort / BPTree strategy — materialize via executeStatement.
		if (hasSort && !orderedLeafScan) {
			for (byte[] key : executeStatement(null, selectSql)) {
				if (!visitor.test(key)) {
					return;
				}
			}
			return;
		}
		final FilterCondition conditionTree = conditionData.conditionTree();
		conditionTree.validate(this.schema);
		final PagingData paging = optimizedQuery.paging();
		if (streamAlwaysTruePrimaryKeys(conditionTree, paging, visitor)) {
			return;
		}
		// No ORDER BY, or composite leaf order matches ORDER BY — LIMIT early-stop is safe.
		final IndexOperationResult operationResult = executeFilterWithPaging(conditionTree, paging, null);
		operationResult.expandBitmapPointers();
		Set<IndexPointerRef> filteredPointers = operationResult.getPointers();
		if (filteredPointers == null || filteredPointers.isEmpty()) {
			return;
		}
		if (rowResolver != null && needsResidualMatch(conditionTree)) {
			for (IndexPointerRef ptr : filteredPointers) {
				final byte[] key = ptr.resolveKey();
				if (key == null) {
					continue;
				}
				final byte[] valueBytes = rowResolver.apply(key);
				if (valueBytes != null && conditionTree.matches(valueBytes, this.schema)) {
					if (!visitor.test(key)) {
						return;
					}
				}
			}
			return;
		}
		final int offset = paging != null ? Math.max(0, paging.offset()) : 0;
		final int limit = paging != null && paging.limit() > 0 ? paging.limit() : UNBOUNDED_KEY_PAGE;
		int skipped = 0;
		int emitted = 0;
		for (IndexPointerRef ptr : filteredPointers) {
			if (skipped < offset) {
				skipped++;
				continue;
			}
			if (limit != UNBOUNDED_KEY_PAGE && emitted >= limit) {
				break;
			}
			final byte[] key = ptr.resolveKey();
			if (key != null) {
				if (!visitor.test(key)) {
					return;
				}
				emitted++;
			}
		}
	}

	/**
	 * AlwaysTrue / no WHERE: PK leaf walk with OFFSET/LIMIT (no searchAll HashSet).
	 *
	 * @return {@code true} when handled
	 */
	private boolean streamAlwaysTruePrimaryKeys(
			FilterCondition conditionTree,
			PagingData paging,
			Predicate<byte[]> visitor
	) {
		if (!(conditionTree instanceof AlwaysTrueCondition)) {
			return false;
		}
		final int offset = paging != null ? Math.max(0, paging.offset()) : 0;
		final int limit = paging != null && paging.limit() > 0 ? paging.limit() : UNBOUNDED_KEY_PAGE;
		final int[] skipped = {0};
		final int[] emitted = {0};
		final boolean found = PkIndexScanUtil.forEachPrimaryKeyRowUntil(
				property2Index,
				compositeIndexes,
				primaryKeyColumns,
				rowKey -> {
					if (skipped[0] < offset) {
						skipped[0]++;
						return true;
					}
					if (limit != UNBOUNDED_KEY_PAGE && emitted[0] >= limit) {
						return false;
					}
					if (!visitor.test(rowKey)) {
						return false;
					}
					emitted[0]++;
					return true;
				});
		if (!found) {
			throw new IllegalStateException("PRIMARY KEY index missing for " + tableLabel);
		}
		return true;
	}

	/**
	 * Lightweight EXPLAIN probe: if {@code sql} filter optimizes onto a composite index EQ,
	 * return the catalog index name; otherwise {@code null}. Does not execute the scan.
	 */
	public String findApplicableCompositeIndexName(String sql) {
		if (sql == null || sql.isBlank() || compositeIndexes.isEmpty()) {
			return null;
		}
		try {
			final QueryData queryData = QueryParser.parseAndBuildCondition(null, sql, compositeIndexes);
			if (queryData.filter() == null || queryData.filter().conditionTree() == null) {
				return null;
			}
			final SortOrderData sortOrder = queryData.sortOrder() != null && queryData.sortOrder().length == 1 ? queryData.sortOrder()[0] : null;
			final FilterConditionData optimized = QueryOptimizer.tryOptimizeForCompositeIndex(compositeIndexes, property2Index, queryData.filter().conditionTree(), sortOrder);
			final AbstractIndexOperation<byte[][], CompositeTreeKey> used = CompositeIndexProbeOps.findCompositeIndexInTree(optimized.conditionTree());
			if (used == null) {
				return null;
			}
			for (Entry<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> entry : compositeIndexes.entrySet()) {
				if (entry.getValue() != used) {
					continue;
				}
				final String catalogName = catalogNameForCompositeColumns(entry.getKey());
				return catalogName != null ? catalogName : used.getIndexName();
			}
			return used.getIndexName();
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private String catalogNameForCompositeColumns(List<String> columns) {
		for (IndexDef def : namedIndexes.values()) {
			if (def.columns().size() != columns.size()) {
				continue;
			}
			boolean match = true;
			for (int i = 0; i < columns.size(); i++) {
				if (!def.columns().get(i).equalsIgnoreCase(columns.get(i))) {
					match = false;
					break;
				}
			}
			if (match) {
				return def.name();
			}
		}
		return null;
	}

	public void forEachSingleColumnBPTree(BiConsumer<String, AbstractBPTree<byte[], SingleTreeKey>> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		for (Entry<String, AbstractIndexOperation<byte[], SingleTreeKey>> entry : property2Index.entrySet()) {
			AbstractIndexOperation<byte[], SingleTreeKey> operation = entry.getValue();
			if (operation instanceof SealedFallbackIndex fallback) {
				operation = fallback.delegate();
			}
			if (operation instanceof AbstractBPTree<byte[], SingleTreeKey> tree) {
				consumer.accept(entry.getKey(), tree);
			}
		}
	}

	/**
	 * Crude per-column fan-out hints for {@code ANALYZE}: {@code tableRows / distinctKeys}.
	 */
	public Map<String, Long> approxColumnFanOut(long tableRows) {
		final long rows = Math.max(1L, tableRows);
		final Map<String, Long> out = new LinkedHashMap<>();
		for (Entry<String, AbstractIndexOperation<byte[], SingleTreeKey>> entry : property2Index.entrySet()) {
			AbstractIndexOperation<byte[], SingleTreeKey> operation = entry.getValue();
			if (operation instanceof SealedFallbackIndex fallback) {
				operation = fallback.delegate();
			} else if (operation instanceof SealedFallbackBitmap bitmapFallback) {
				operation = bitmapFallback.delegate();
			}
			long distinct = 0L;
			if (operation instanceof AbstractBPTree<?, ?> tree) {
				distinct = tree.cardinality();
			} else if (operation instanceof GridBitmapIndex bitmap) {
				distinct = Math.max(1L, bitmap.approxPostings());
			}
			if (distinct <= 0L) {
				out.put(entry.getKey(), rows);
			} else {
				out.put(entry.getKey(), QueryCardinality.estimateFanOut(rows, distinct));
			}
		}
		return out;
	}

	/**
	 * Visit single-column {@link IndexType#BITMAP} indexes only (opt-in; not BPTree).
	 */
	public void forEachSingleColumnBitmap(BiConsumer<String, GridBitmapIndex> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		for (Entry<String, AbstractIndexOperation<byte[], SingleTreeKey>> entry : property2Index.entrySet()) {
			AbstractIndexOperation<byte[], SingleTreeKey> operation = entry.getValue();
			if (operation instanceof SealedFallbackBitmap fallback) {
				operation = fallback.delegate();
			}
			if (operation instanceof GridBitmapIndex bitmap) {
				consumer.accept(entry.getKey(), bitmap);
			}
		}
	}

	/**
	 * Visit multi-column BPTree indexes; sealed file name is
	 * {@link org.genfork.grid.replication.snapshot.sealed.SealedCompositeIndexNames#of(List)}.
	 */
	public void forEachCompositeBPTree(BiConsumer<List<String>, AbstractBPTree<byte[][], CompositeTreeKey>> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		for (Entry<List<String>, AbstractIndexOperation<byte[][], CompositeTreeKey>> entry : compositeIndexes.entrySet()) {
			AbstractIndexOperation<byte[][], CompositeTreeKey> operation = entry.getValue();
			if (operation instanceof SealedFallbackCompositeIndex fallback) {
				operation = fallback.delegate();
			}
			if (operation instanceof AbstractBPTree<byte[][], CompositeTreeKey> tree) {
				consumer.accept(entry.getKey(), tree);
			}
		}
	}

	/**
	 * Replace a BITMAP working-set index after sealed hydrate (no-op if property is not BITMAP).
	 * Prefer {@link #installSealedBitmapFallback} for cold-merge (RAM + {@code .sbm}).
	 */
	public void replaceBitmapIndex(String property, GridBitmapIndex loaded) {
		Objects.requireNonNull(property, "property");
		Objects.requireNonNull(loaded, "loaded");
		property2Index.computeIfPresent(property, (_, current) -> {
			if (current instanceof GridBitmapIndex || current instanceof SealedFallbackBitmap) {
				return loaded;
			}
			return current;
		});
		optimizedPlanCache.clear();
	}

	/**
	 * Attach sealed {@code .sbm} as cold-key fallback for a BITMAP index (merge, not wipe).
	 */
	public void installSealedBitmapFallback(String property, int shard, GridBitmapIndex sealed) {
		Objects.requireNonNull(property, "property");
		Objects.requireNonNull(sealed, "sealed");
		property2Index.computeIfPresent(property, (_, current) -> {
			final SealedFallbackBitmap fallback;
			if (current instanceof SealedFallbackBitmap existing) {
				fallback = existing;
			} else if (current instanceof GridBitmapIndex bitmap) {
				fallback = new SealedFallbackBitmap(bitmap);
			} else {
				return current;
			}
			fallback.addSealed(shard, sealed);
			return fallback;
		});
		optimizedPlanCache.clear();
	}

	public void installSealedFallback(String property, SealedBPTreeReader reader) {
		Objects.requireNonNull(property, "property");
		Objects.requireNonNull(reader, "reader");
		property2Index.computeIfPresent(property, (_, current) -> {
			final SealedFallbackIndex fallback;
			if (current instanceof SealedFallbackIndex existing) {
				fallback = existing;
			} else if (current instanceof AbstractBPTree<?, ?>) {
				fallback = new SealedFallbackIndex(current);
			} else {
				return current;
			}
			fallback.addReader(reader);
			return fallback;
		});
		optimizedPlanCache.clear();
	}

	/**
	 * Attach sealed {@code .sbpt} readers as cold-key fallback for a multi-column BPTree.
	 */
	public void installSealedCompositeFallback(List<String> columns, SealedBPTreeReader reader) {
		Objects.requireNonNull(columns, "columns");
		Objects.requireNonNull(reader, "reader");
		compositeIndexes.computeIfPresent(columns, (_, current) -> {
			final SealedFallbackCompositeIndex fallback;
			if (current instanceof SealedFallbackCompositeIndex existing) {
				fallback = existing;
			} else if (current instanceof AbstractBPTree<?, ?>) {
				fallback = new SealedFallbackCompositeIndex(current);
			} else {
				return current;
			}
			fallback.addReader(reader);
			return fallback;
		});
		optimizedPlanCache.clear();
	}

	/**
	 * Mark sealed fallback indexes as RAM-authoritative after FULL hydrate without WS eviction.
	 * When true, non-empty RAM EQ/range skips sealed .sbpt merge.
	 */
	public void setSealedRamAuthoritative(boolean authoritative) {
		for (AbstractIndexOperation<byte[], SingleTreeKey> operation : property2Index.values()) {
			if (operation instanceof SealedFallbackIndex fallback) {
				fallback.setRamAuthoritative(authoritative);
			} else if (operation instanceof SealedFallbackBitmap bitmapFallback) {
				bitmapFallback.setRamAuthoritative(authoritative);
			}
		}
		for (AbstractIndexOperation<byte[][], CompositeTreeKey> operation : compositeIndexes.values()) {
			if (operation instanceof SealedFallbackCompositeIndex fallback) {
				fallback.setRamAuthoritative(authoritative);
			}
		}
	}

	public void clearSealedIndexes() {
		property2Index.replaceAll((_, current) -> {
			if (current instanceof SealedFallbackIndex fallback) {
				return fallback.delegate();
			}
			if (current instanceof SealedFallbackBitmap bitmapFallback) {
				return bitmapFallback.delegate();
			}
			return current;
		});
		compositeIndexes.replaceAll((_, current) -> current instanceof SealedFallbackCompositeIndex fallback ? fallback.delegate() : current);
		optimizedPlanCache.clear();
	}

	private void initOrderIndexFields(FieldMetaData[] orderIndexFields) {
		if (orderIndexFields == null) {
			return;
		}
		this.cachedOrderIndexFields = new HashMap<>();
		for (int i = 0; i < orderIndexFields.length; i++) {
			final FieldMetaData fieldMetaData = orderIndexFields[i];
			this.cachedOrderIndexFields.put(fieldMetaData.getName(), new ArrayIndexType(i, fieldMetaData.getType()));
		}
	}

	private void wireIndexes(Map<IndexType, List<HashField[]>> indexes) {
		for (Entry<IndexType, List<HashField[]>> entry : indexes.entrySet()) {
			final IndexType indexType = entry.getKey();
			final boolean strict = indexType == IndexType.STRICT;
			final List<HashField[]> list = entry.getValue();
			for (HashField[] fields : list) {
				if (fields.length == 1) {
					final String fieldName = fields[0].field().getName();
					final AbstractIndexOperation<byte[], SingleTreeKey> indexTree = indexType == IndexType.BITMAP ? new GridBitmapIndex(fieldName + "-BITMAP", BITMAP_INDEX_INITIAL_CAPACITY) : new GridPointerBPTree(fieldName + "-" + indexType.name(), strict);
					property2Index.put(fieldName, indexTree);
					revertProperty2Index.put(fieldName, new ConcurrentHashMap<>());
				} else if (indexType == IndexType.BITMAP) {
					throw new IllegalArgumentException(IndexDef.BITMAP_SINGLE_COLUMN_ONLY);
				} else {
					final List<String> combination = Arrays.stream(fields).map(f -> f.field().getName()).collect(Collectors.toList());
					compositeIndexes.put(combination, new GridPointerCompositeBPTree(combination, combination + "-" + indexType.name(), strict));
				}
			}
		}
	}

	private void scheduleDumpStats() {
		cancelDumpStats();
		dumpStatsFuture = ThreadService.getScheduledExecutor().scheduleWithFixedDelay(() -> {
			for (Entry<String, AbstractIndexOperation<byte[], SingleTreeKey>> entry : property2Index.entrySet()) {
				final String fieldName = entry.getKey();
				final AbstractIndexOperation<?, ? extends TreeKey<?>> indexOp = entry.getValue();
				indexOp.dumpStats(tableLabel + "#" + fieldName);
			}
		}, 300, 300, TimeUnit.SECONDS);
	}

	/**
	 * Stop periodic dumpStats (store close / JMH TearDown).
	 */
	public void cancelDumpStats() {
		final ScheduledFuture<?> future = dumpStatsFuture;
		if (future != null) {
			future.cancel(false);
			dumpStatsFuture = null;
		}
	}

	public void putIndexKeyValues(long keyNativePointer, byte[] keyArray, Map<String, byte[]> fieldValues) {
		final IndexPointerRef keyPointerRef = new IndexPointerRef(keyNativePointer, keyArray);
		removeIndexPointersForKey(keyArray);
		putSingleColumnIndexValues(keyPointerRef, keyArray, fieldValues);
		for (List<String> indexFields : compositeIndexes.keySet()) {
			putCompositeIndexValues(keyPointerRef, keyArray, indexFields, fieldValues);
		}
	}

	/**
	 * Insert into one named secondary index only (CREATE INDEX backfill).
	 * Does not touch unrelated trees.
	 */
	public void putIndexKeyValuesForNamed(IndexDef def, long keyNativePointer, byte[] keyArray, Map<String, byte[]> fieldValues) {
		Objects.requireNonNull(def, "def");
		final IndexPointerRef keyPointerRef = new IndexPointerRef(keyNativePointer, keyArray);
		if (def.columns().size() == 1) {
			final String fieldName = def.columns().getFirst();
			final byte[] value = fieldValues.get(fieldName);
			if (value == null) {
				return;
			}
			final AbstractIndexOperation<byte[], SingleTreeKey> tree = property2Index.get(fieldName);
			if (tree == null) {
				return;
			}
			removePriorSingleColumn(fieldName, keyArray);
			final SingleTreeKey treeKey = new SingleTreeKey(value);
			tree.insert(treeKey, keyPointerRef);
			revertProperty2Index.computeIfAbsent(fieldName, _ -> new ConcurrentHashMap<>()).put(new ByteArrayContainer(keyArray), new RevertSingle(treeKey, keyPointerRef));
			return;
		}
		final List<String> combination = new ArrayList<>(def.columns());
		removePriorComposite(combination, keyArray);
		putCompositeIndexValues(keyPointerRef, keyArray, combination, fieldValues);
	}

	private void putSingleColumnIndexValues(IndexPointerRef keyPointerRef, byte[] keyArray, Map<String, byte[]> fieldValues) {
		for (Entry<String, byte[]> entry : fieldValues.entrySet()) {
			final String fieldName = entry.getKey();
			final AbstractIndexOperation<byte[], SingleTreeKey> tree = property2Index.get(fieldName);
			if (tree == null) {
				continue;
			}
			final SingleTreeKey treeKey = new SingleTreeKey(entry.getValue());
			tree.insert(treeKey, keyPointerRef);
			revertProperty2Index.computeIfAbsent(fieldName, _ -> new ConcurrentHashMap<>()).put(new ByteArrayContainer(keyArray), new RevertSingle(treeKey, keyPointerRef));
		}
	}

	private void putCompositeIndexValues(IndexPointerRef keyPointerRef, byte[] keyArray, List<String> indexFields, Map<String, byte[]> fieldValues) {
		final AbstractIndexOperation<byte[][], CompositeTreeKey> tree = compositeIndexes.get(indexFields);
		if (tree == null) {
			return;
		}
		final byte[][] valuesArray = new byte[indexFields.size()][];
		for (int i = 0; i < indexFields.size(); i++) {
			valuesArray[i] = fieldValues.getOrDefault(indexFields.get(i), ArrayUtils.EMPTY_BYTE_ARRAY);
		}
		final CompositeTreeKey treeKey = new CompositeTreeKey(valuesArray);
		tree.insert(treeKey, keyPointerRef);
		revertCompositeProperty2Index.computeIfAbsent(indexFields, _ -> new ConcurrentHashMap<>()).put(new ByteArrayContainer(keyArray), new RevertComposite(treeKey, keyPointerRef));
	}

	public void startAnalyze() {
		if (!CollectionUtils.isEmpty(cachedOrderIndexFields)) {
			for (String fieldName : cachedOrderIndexFields.keySet()) {
				final AbstractIndexOperation<byte[], SingleTreeKey> index = property2Index.get(fieldName);
				if (index != null) {
					index.processAnalyze();
				}
			}
		}
	}

	public List<byte[]> executeStatement(ExplainQuery.QueryPlan queryPlan, String sql) {
		return executeStatement(queryPlan, sql, null);
	}

	/**
	 * Execute SELECT key plan; pass {@code resolvedWhereSubqueries} when WHERE IN/scalar subqueries
	 * were pre-evaluated (see {@link org.genfork.grid.sql.exec.SqlQueryExecutor#select}).
	 */
	public List<byte[]> executeStatement(ExplainQuery.QueryPlan queryPlan, String sql, List<FilterCondition> resolvedWhereSubqueries) {
		final QueryData optimizedQuery;
		final boolean hasResolved = resolvedWhereSubqueries != null && !resolvedWhereSubqueries.isEmpty();
		if (queryPlan == null && sql != null && !sql.isBlank() && !hasResolved) {
			optimizedQuery = optimizedPlanCache.computeIfAbsent(sql.trim(), this::buildOptimizedQuery);
		} else {
			optimizedQuery = buildOptimizedQuery(sql, queryPlan, resolvedWhereSubqueries);
		}
		final FilterConditionData conditionData = optimizedQuery.filter();
		if (conditionData == null) {
			return Collections.emptyList();
		}
		if (optimizedQuery.paging() != null && optimizedQuery.paging().limit() == 0) {
			return Collections.emptyList();
		}
		if (optimizedQuery.isAggregate()) {
			return executeAggregate(queryPlan, optimizedQuery);
		}
		if (optimizedQuery.isJoin()) {
			return executeJoin(queryPlan, optimizedQuery);
		}
		final FilterCondition conditionTree = conditionData.conditionTree();
		conditionTree.validate(this.schema);
		validateOrderIfNeeded(optimizedQuery.sortOrder());
		final PagingData paging = optimizedQuery.paging();
		final boolean hasSort = optimizedQuery.sortOrder() != null && optimizedQuery.sortOrder().length > 0;
		final boolean orderedLeafScan = conditionData.useSameOrderAscendingIndex();
		// AlwaysTrue: leaf stream → key list (no searchAll HashSet). LIMIT early-stop when no unordered ORDER BY.
		if (!(hasSort && !orderedLeafScan) && conditionTree instanceof AlwaysTrueCondition) {
			final List<byte[]> keys = new ArrayList<>();
			streamAlwaysTruePrimaryKeys(conditionTree, paging, key -> {
				keys.add(key);
				return true;
			});
			if (queryPlan != null) {
				final IndexOperationResult stub = new IndexOperationResult();
				stub.setSize(keys.size());
				recordScanCost(queryPlan, optimizedQuery, stub);
			}
			return keys;
		}
		// TD-PERF-002: push LIMIT into EQ/prefix only when leaf order already matches ORDER BY
		// (or there is no ORDER BY). Unordered ORDER BY must collect full candidates first.
		final PagingData filterPaging = QueryPagingContext.forFilterEarlyStop(paging, hasSort && !orderedLeafScan);
		// One path: safe LIMIT push-down (direct CompositeIndexCondition when possible),
		// optional residual match, then scan strategy (TableScan when pointers already ordered).
		final IndexOperationResult operationResult = executeFilterWithPaging(conditionTree, filterPaging, queryPlan);
		operationResult.expandBitmapPointers();
		Set<IndexPointerRef> filteredPointers = operationResult.getPointers();
		if (filteredPointers == null || filteredPointers.isEmpty()) {
			if (queryPlan != null) {
				recordScanCost(queryPlan, optimizedQuery, operationResult);
			}
			return Collections.emptyList();
		}
		if (rowResolver != null && needsResidualMatch(conditionTree)) {
			// Ordered leaf: preserve encounter order with ArrayList; unordered needs Set semantics.
			final Set<IndexPointerRef> residual = orderedLeafScan ? new LinkedHashSet<>(IndexPointerRef.listCapacity(filterPaging != null && filterPaging.hasBoundedLimit() ? Math.max(0, filterPaging.offset()) + filterPaging.limit() : 16)) : new LinkedHashSet<>();
			for (IndexPointerRef ptr : filteredPointers) {
				final byte[] key = ptr.resolveKey();
				if (key == null) {
					continue;
				}
				final byte[] valueBytes = rowResolver.apply(key);
				if (valueBytes != null && conditionTree.matches(valueBytes, this.schema)) {
					residual.add(ptr);
					// Ordered leaf + LIMIT: residual cannot invent earlier rows — stop at page need.
					if (orderedLeafScan && filterPaging != null && filterPaging.hasBoundedLimit()) {
						final int need = Math.max(0, filterPaging.offset()) + filterPaging.limit();
						if (residual.size() >= need) {
							break;
						}
					}
				}
			}
			operationResult.setPointers(residual);
			operationResult.setSize(residual.size());
			if (residual.isEmpty()) {
				if (queryPlan != null) {
					recordScanCost(queryPlan, optimizedQuery, operationResult);
				}
				return Collections.emptyList();
			}
		} else if (operationResult.getSize() <= 0) {
			operationResult.setSize(filteredPointers.size());
		}
		// Indexed ordered scan: pointers already follow ORDER BY — page and stop (no re-sort).
		if (orderedLeafScan && hasSort) {
			if (queryPlan != null) {
				recordScanCost(queryPlan, optimizedQuery, operationResult);
			}
			return TableScanStrategy.INSTANCE.execute(queryPlan, operationResult, optimizedQuery.sortOrder(), paging);
		}
		final QueryOptimizer.ScanChoice choice = recordScanCost(queryPlan, optimizedQuery, operationResult);
		return choice.strategy().execute(queryPlan, operationResult, optimizedQuery.sortOrder(), optimizedQuery.paging());
	}

	/**
	 * Filter execution with optional LIMIT early-stop. Hot path: {@link CompositeIndexCondition}
	 * uses {@link CompositeIndexCondition#executeLimited} directly (no ScopedValue).
	 */
	private IndexOperationResult executeFilterWithPaging(FilterCondition conditionTree, PagingData filterPaging, ExplainQuery.QueryPlan queryPlan) {
		if (conditionTree instanceof CompositeIndexCondition composite && filterPaging != null && filterPaging.hasBoundedLimit()) {
			final int maxPointers = Math.max(0, filterPaging.offset()) + filterPaging.limit();
			return composite.executeLimited(queryPlan, maxPointers);
		}
		return QueryPagingContext.callWithPaging(filterPaging, () -> conditionTree.execute(property2Index, compositeIndexes, primaryKeyColumns, queryPlan));
	}

	private QueryOptimizer.ScanChoice recordScanCost(ExplainQuery.QueryPlan queryPlan, QueryData optimizedQuery, IndexOperationResult operationResult) {
		final QueryOptimizer.ScanChoice choice = QueryOptimizer.chooseBestScanStrategy(optimizedQuery, property2Index, cachedOrderIndexFields, operationResult, currentTableRowStats());
		if (queryPlan != null) {
			ExplainQuery.recordEstimatedCost(queryPlan, choice.estimatedCost());
			final TableRowStats stats = currentTableRowStats();
			final ExplainQuery.QueryPlanNode costNode = ExplainQuery.startNode(queryPlan, "COST", choice.kind() + " estCost=" + String.format(Locale.ROOT, "%.2f", choice.estimatedCost()) + " stats=" + stats.source());
			ExplainQuery.recordEstimatedCost(costNode, choice.estimatedCost());
			ExplainQuery.endNode(costNode, operationResult.getSize(), operationResult.getSize());
			queryPlan.completeCurrentNode();
		}
		return choice;
	}

	private TableRowStats currentTableRowStats() {
		final Supplier<TableRowStats> supplier = tableRowStatsSupplier;
		if (supplier == null) {
			return TableRowStats.UNKNOWN;
		}
		final TableRowStats stats = supplier.get();
		return stats == null ? TableRowStats.UNKNOWN : stats;
	}

	private QueryData buildOptimizedQuery(String sql) {
		return buildOptimizedQuery(sql, null, null);
	}

	private QueryData buildOptimizedQuery(String sql, ExplainQuery.QueryPlan queryPlan) {
		return buildOptimizedQuery(sql, queryPlan, null);
	}

	private QueryData buildOptimizedQuery(String sql, ExplainQuery.QueryPlan queryPlan, List<FilterCondition> resolvedWhereSubqueries) {
		final QueryData queryData = QueryParser.parseAndBuildCondition(queryPlan, sql, compositeIndexes, resolvedWhereSubqueries);
		final FilterConditionData conditionData = queryData.filter();
		if (conditionData == null) {
			return queryData;
		}
		final FilterCondition bitmapPreferred = QueryOptimizer.preferBitmapPredicates(property2Index, conditionData.conditionTree());
		final FilterConditionData optimized = QueryOptimizer.tryOptimizeForCompositeIndex(compositeIndexes, property2Index, bitmapPreferred, queryData.sortOrder() != null && queryData.sortOrder().length == 1 ? queryData.sortOrder()[0] : null);
		return new QueryData(queryData.table(), queryData.fields(), optimized, queryData.paging(), queryData.sortOrder(), queryData.aggregate(), queryData.joins());
	}

	private List<byte[]> executeAggregate(ExplainQuery.QueryPlan queryPlan, QueryData queryData) {
		final AggregateSpec aggregate = queryData.aggregate();
		ExplainQuery.QueryPlanNode node = null;
		if (queryPlan != null) {
			node = ExplainQuery.startNode(queryPlan, "AGGREGATE", String.valueOf(aggregate));
		}
		try {
			final FilterCondition conditionTree = queryData.filter().conditionTree();
			conditionTree.validate(this.schema);
			final IndexOperationResult operationResult = conditionTree.execute(property2Index, compositeIndexes, primaryKeyColumns, queryPlan);
			final Set<IndexPointerRef> pointers = operationResult.getPointers();
			if (pointers == null || pointers.isEmpty() || rowResolver == null || this.schema == null) {
				return Collections.emptyList();
			}
			final List<String> groupCols = aggregate.groupByColumns();
			if (groupCols == null || groupCols.isEmpty()) {
				throw new ConditionValidationException("criteria.unsupported-aggregate", new String[] {"aggregates require GROUP BY <col>"});
			}
			final List<FieldMetaData> groupFields = new ArrayList<>(groupCols.size());
			for (String groupCol : groupCols) {
				final FieldMetaData groupField = resolveCatalogField(groupCol);
				if (groupField == null) {
					throw new ConditionValidationException("criteria.mismatch-property", new String[] {groupCol});
				}
				groupFields.add(groupField);
			}
			FieldMetaData valueField = null;
			if (aggregate.sumColumn() != null) {
				valueField = resolveCatalogField(aggregate.sumColumn());
				if (valueField == null) {
					throw new ConditionValidationException("criteria.mismatch-property", new String[] {aggregate.sumColumn()});
				}
			}
			if (aggregate.countStar()) {
				final Map<WireFieldBytes, Long> counts = new LinkedHashMap<>();
				for (IndexPointerRef ptr : pointers) {
					final byte[] valueBytes = resolveMatchingValue(ptr, conditionTree);
					if (valueBytes == null) {
						continue;
					}
					final WireFieldBytes groupKey = CompositeIndexProbeOps.readCompositeFieldBytes(valueBytes, this.schema, groupFields);
					counts.merge(groupKey, 1L, Long::sum);
				}
				return CompositeIndexProbeOps.formatAggRows(counts);
			}
			final Map<WireFieldBytes, double[]> sums = new LinkedHashMap<>();
			for (IndexPointerRef ptr : pointers) {
				final byte[] valueBytes = resolveMatchingValue(ptr, conditionTree);
				if (valueBytes == null) {
					continue;
				}
				final WireFieldBytes groupKey = CompositeIndexProbeOps.readCompositeFieldBytes(valueBytes, this.schema, groupFields);
				final byte[] rawWire = CompositeIndexProbeOps.readFieldBytes(valueBytes, this.schema, valueField).bytes();
				final double v = WireFieldCompare.numeric(rawWire, valueField.getType());
				final double[] acc = sums.computeIfAbsent(groupKey, _ -> {
					final double[] init = new double[3];
					init[0] = 0.0;
					init[1] = 0.0;
					init[2] = Double.NaN;
					return init;
				});
				if (aggregate.min() || aggregate.max()) {
					if (Double.isNaN(acc[2])) {
						acc[2] = v;
					} else if (aggregate.min()) {
						acc[2] = Math.min(acc[2], v);
					} else {
						acc[2] = Math.max(acc[2], v);
					}
				} else {
					acc[0] += v;
				}
				acc[1] += 1.0;
			}
			final List<byte[]> out = new ArrayList<>(sums.size());
			for (Map.Entry<WireFieldBytes, double[]> e : sums.entrySet()) {
				final double[] acc = e.getValue();
				final double result;
				if (aggregate.min() || aggregate.max()) {
					result = Double.isNaN(acc[2]) ? 0.0 : acc[2];
				} else if (aggregate.avg()) {
					result = acc[1] == 0 ? 0 : acc[0] / acc[1];
				} else {
					result = acc[0];
				}
				final String line = e.getKey().toString() + '\t' + result;
				out.add(line.getBytes(StandardCharsets.UTF_8));
			}
			return out;
		} finally {
			if (node != null) {
				ExplainQuery.endNode(node, -1, -1);
				queryPlan.completeCurrentNode();
			}
		}
	}

	private byte[] resolveMatchingValue(IndexPointerRef ptr, FilterCondition conditionTree) {
		final byte[] key = ptr.resolveKey();
		if (key == null) {
			return null;
		}
		final byte[] valueBytes = rowResolver.apply(key);
		if (valueBytes == null || !conditionTree.matches(valueBytes, this.schema)) {
			return null;
		}
		return valueBytes;
	}

	private FieldMetaData resolveCatalogField(String column) {
		if (column == null) {
			return null;
		}
		return fieldsByName.get(column.toLowerCase(Locale.ROOT));
	}

	/**
	 * Inner JOIN nested-loop (v1): both sides resolved via this index's {@code rowResolver}
	 * (self-join / same physical store). ON leftCol = rightCol.
	 */
	private List<byte[]> executeJoin(ExplainQuery.QueryPlan queryPlan, QueryData queryData) {
		final JoinSpec join = queryData.joins().getFirst();
		ExplainQuery.QueryPlanNode node = null;
		if (queryPlan != null) {
			node = ExplainQuery.startNode(queryPlan, "JOIN", String.valueOf(join));
		}
		try {
			final FilterCondition conditionTree = queryData.filter().conditionTree();
			conditionTree.validate(this.schema);
			final IndexOperationResult operationResult = conditionTree.execute(property2Index, compositeIndexes, primaryKeyColumns, queryPlan);
			final Set<IndexPointerRef> leftPointers = operationResult.getPointers();
			if (leftPointers == null || leftPointers.isEmpty() || rowResolver == null) {
				return Collections.emptyList();
			}
			final FieldMetaData leftField = resolveCatalogField(join.leftColumn());
			final FieldMetaData rightField = resolveCatalogField(join.rightColumn());
			if (leftField == null || rightField == null) {
				throw new ConditionValidationException("criteria.mismatch-property", new String[] {join.leftColumn() + "/" + join.rightColumn()});
			}
			final List<byte[]> leftRows = new ArrayList<>();
			for (IndexPointerRef ptr : leftPointers) {
				final byte[] valueBytes = resolveMatchingValue(ptr, conditionTree);
				if (valueBytes != null) {
					leftRows.add(valueBytes);
				}
			}
			// Right side: full scan via AlwaysTrue for nested-loop join.
			final IndexOperationResult rightOps = AlwaysTrueCondition.getInstance().execute(property2Index, compositeIndexes, primaryKeyColumns, queryPlan);
			final Set<IndexPointerRef> rightPointers = rightOps.getPointers();
			final Map<WireFieldBytes, List<byte[]>> rightByKey = new HashMap<>();
			if (rightPointers != null) {
				for (IndexPointerRef ptr : rightPointers) {
					final byte[] key = ptr.resolveKey();
					if (key == null) {
						continue;
					}
					final byte[] valueBytes = rowResolver.apply(key);
					if (valueBytes == null) {
						continue;
					}
					final WireFieldBytes rk = CompositeIndexProbeOps.readFieldBytes(valueBytes, this.schema, rightField);
					rightByKey.computeIfAbsent(rk, _ -> new ArrayList<>()).add(valueBytes);
				}
			}
			final List<byte[]> out = new ArrayList<>();
			final int limit = queryData.paging() != null ? queryData.paging().limit() : Integer.MAX_VALUE;
			for (byte[] left : leftRows) {
				final WireFieldBytes lk = CompositeIndexProbeOps.readFieldBytes(left, this.schema, leftField);
				final List<byte[]> matches = rightByKey.get(lk);
				if (matches == null) {
					continue;
				}
				for (byte[] right : matches) {
					final String line = lk + "\t" + Arrays.toString(left) + '\t' + Arrays.toString(right);
					out.add(line.getBytes(StandardCharsets.UTF_8));
					if (out.size() >= limit) {
						return out;
					}
				}
			}
			return out;
		} finally {
			if (node != null) {
				ExplainQuery.endNode(node, -1, -1);
				queryPlan.completeCurrentNode();
			}
		}
	}

	private void validateOrderIfNeeded(SortOrderData[] orderData) {
		if (orderData == null) {
			return;
		}
		if (CollectionUtils.isEmpty(cachedOrderIndexFields)) {
			return;
		}
		for (SortOrderData data : orderData) {
			final String sortField = data.sortField();
			if (!cachedOrderIndexFields.containsKey(sortField)) {
				throw new ConditionValidationException("criteria.mismatch-property", new String[] {sortField});
			}
		}
	}

	public byte[] remove(byte[] key) {
		removeIndexPointersForKey(key);
		return null;
	}

	/**
	 * Drop prior LAX/STRICT postings for this PK from every secondary tree (writer upsert path).
	 */
	private void removeIndexPointersForKey(byte[] keyArray) {
		if (keyArray == null) {
			return;
		}
		final ByteArrayContainer keyContainer = new ByteArrayContainer(keyArray);
		for (Entry<String, Map<ByteArrayContainer, RevertSingle>> entry : revertProperty2Index.entrySet()) {
			final RevertSingle prior = entry.getValue().remove(keyContainer);
			if (prior == null) {
				continue;
			}
			final AbstractIndexOperation<byte[], SingleTreeKey> tree = property2Index.get(entry.getKey());
			if (tree != null) {
				tree.deletePointer(prior.treeKey(), prior.pointer());
			}
		}
		for (Entry<List<String>, Map<ByteArrayContainer, RevertComposite>> entry : revertCompositeProperty2Index.entrySet()) {
			final RevertComposite prior = entry.getValue().remove(keyContainer);
			if (prior == null) {
				continue;
			}
			final AbstractIndexOperation<byte[][], CompositeTreeKey> tree = compositeIndexes.get(entry.getKey());
			if (tree != null) {
				tree.deletePointer(prior.treeKey(), prior.pointer());
			}
		}
	}

	private void removePriorSingleColumn(String fieldName, byte[] keyArray) {
		final Map<ByteArrayContainer, RevertSingle> revert = revertProperty2Index.get(fieldName);
		if (revert == null) {
			return;
		}
		final RevertSingle prior = revert.remove(new ByteArrayContainer(keyArray));
		if (prior == null) {
			return;
		}
		final AbstractIndexOperation<byte[], SingleTreeKey> tree = property2Index.get(fieldName);
		if (tree != null) {
			tree.deletePointer(prior.treeKey(), prior.pointer());
		}
	}

	private void removePriorComposite(List<String> indexFields, byte[] keyArray) {
		final Map<ByteArrayContainer, RevertComposite> revert = revertCompositeProperty2Index.get(indexFields);
		if (revert == null) {
			return;
		}
		final RevertComposite prior = revert.remove(new ByteArrayContainer(keyArray));
		if (prior == null) {
			return;
		}
		final AbstractIndexOperation<byte[][], CompositeTreeKey> tree = compositeIndexes.get(indexFields);
		if (tree != null) {
			tree.deletePointer(prior.treeKey(), prior.pointer());
		}
	}

	/**
	 * Residual post-filter when any leaf predicate uses an unindexed column (PK scan candidates).
	 */
	private boolean needsResidualMatch(FilterCondition condition) {
		return switch (condition) {
			case null -> false;
			case AlwaysTrueCondition _, AlwaysFalseCondition _, CompositeIndexCondition _ -> false;
			case LogicalOperatorCondition loc -> !property2Index.containsKey(loc.getField());
			case AndCondition and -> needsResidualMatch(and.getLeft()) || needsResidualMatch(and.getRight());
			case OrCondition or -> needsResidualMatch(or.getLeft()) || needsResidualMatch(or.getRight());
			case NotCondition not -> needsResidualMatch(not.getChild());
			default -> true;
		};
	}

	public void clear() {
		cancelDumpStats();
		optimizedPlanCache.clear();
		QueryParser.clearPlanCache();
		for (AbstractIndexOperation<byte[], SingleTreeKey> index : property2Index.values()) {
			index.clear();
		}
		revertProperty2Index.clear();
		revertCompositeProperty2Index.clear();
	}

	/**
	 * Optional key → valueBytes resolver for residual (unindexed) filter matches.
	 */
	public void setRowResolver(final Function<byte[], byte[]> rowResolver) {
		this.rowResolver = rowResolver;
	}

	/**
	 * Optional map/sealed size hints for {@link QueryOptimizer} cost model.
	 */
	public void setTableRowStatsSupplier(final Supplier<TableRowStats> tableRowStatsSupplier) {
		this.tableRowStatsSupplier = tableRowStatsSupplier;
	}
}
