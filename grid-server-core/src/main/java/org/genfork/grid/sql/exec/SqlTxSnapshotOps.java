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
package org.genfork.grid.sql.exec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.query.adaptive.AdaptiveParallelScan;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.query.adaptive.QueryHeavinessEstimator;
import org.genfork.grid.query.distributed.DistributedKeyFanOut;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.WireResidualBatch;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.tx.KeyWrapper;
import org.genfork.grid.store.TableStore;

/**
 * Dist-TX snapshot / peer fan-in helpers for {@link SqlQueryExecutor}.
 * <p>
 * Working set remains {@code byte[]} keys and value blobs; residual match uses
 * {@link FilterCondition#matches(byte[], TableSchema)} (no Object decode).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlTxSnapshotOps {
	private SqlTxSnapshotOps() {
	}

	/**
	 * Blob resolve under pinned snapshot; forces {@link DistTxSnapshot#currentOrZero()} before
	 * each peer fetcher wave so ThreadLocal / {@link DistTxSnapshot.SnapshotAwareBlobFetcher}
	 * share one horizon.
	 */
	static byte[] resolveBlobUnderSnapshot(
			String table,
			TableStore store,
			byte[] key,
			List<BiFunction<String, byte[], byte[]>> peerBlobs
	) {
		DistTxSnapshot.currentOrZero();
		return DistributedKeyFanOut.resolveBlob(table, store, key, peerBlobs);
	}

	static <T> List<T> concat(List<T> committed, List<T> dirty) {
		if (dirty == null || dirty.isEmpty()) {
			return committed == null ? List.of() : committed;
		}
		if (committed == null || committed.isEmpty()) {
			return dirty;
		}
		final List<T> combined = new ArrayList<>(committed.size() + dirty.size());
		combined.addAll(committed);
		combined.addAll(dirty);
		return List.copyOf(combined);
	}

	static Set<KeyWrapper> collectRemoteTombstones(
			String table,
			List<Function<String, List<byte[]>>> tombstoneSuppliers
	) {
		if (tombstoneSuppliers == null || tombstoneSuppliers.isEmpty()) {
			return Set.of();
		}
		final Set<KeyWrapper> out = new LinkedHashSet<>();
		for (Function<String, List<byte[]>> supplier : tombstoneSuppliers) {
			if (supplier == null) {
				continue;
			}
			// Wire DistTxSnapshot into peer tombstone suppliers (ThreadLocal / SnapshotAware).
			final List<byte[]> keys = invokeKeySupplier(supplier, table);
			if (keys == null || keys.isEmpty()) {
				continue;
			}
			for (byte[] key : keys) {
				if (key != null && key.length > 0) {
					out.add(new KeyWrapper(key));
				}
			}
		}
		return out.isEmpty() ? Set.of() : Set.copyOf(out);
	}

	static void mergeRemoteDirtyKeys(
			List<byte[]> keys,
			List<Function<String, List<byte[]>>> dirtyPeers,
			String sql
	) {
		if (dirtyPeers == null || dirtyPeers.isEmpty() || keys == null) {
			return;
		}
		final Set<KeyWrapper> seen = new LinkedHashSet<>();
		for (byte[] key : keys) {
			if (key != null) {
				seen.add(new KeyWrapper(key));
			}
		}
		int added = 0;
		int sources = 0;
		for (Function<String, List<byte[]>> supplier : dirtyPeers) {
			if (supplier == null) {
				continue;
			}
			sources++;
			final List<byte[]> peerKeys = invokeKeySupplier(supplier, sql);
			if (peerKeys == null || peerKeys.isEmpty()) {
				continue;
			}
			for (byte[] key : peerKeys) {
				if (key == null || key.length == 0) {
					continue;
				}
				final KeyWrapper kw = new KeyWrapper(key);
				if (seen.add(kw)) {
					keys.add(key);
					added++;
				}
			}
		}
		if (sources > 0) {
			DistributedQueryMetrics.recordFanIn(sources, added);
		}
	}

	/**
	 * Invoke a peer key/tombstone supplier with {@link DistTxSnapshot#currentOrZero()} visible
	 * on this thread ({@link DistTxSnapshot.SnapshotAwareKeySupplier} reads it in {@code apply}).
	 */
	static List<byte[]> invokeKeySupplier(
			Function<String, List<byte[]>> supplier,
			String sqlOrTable
	) {
		DistTxSnapshot.currentOrZero();
		return supplier.apply(sqlOrTable);
	}

	static void forEachSnapshotCommitted(
			TableStore store,
			TableSchema schema,
			FilterCondition filter,
			List<byte[]> blobs
	) {
		final boolean indexedEq = isIndexedEqSnapshot(store, filter);
		final QueryHeaviness heaviness = QueryHeavinessEstimator.fromFilter(store, filter, indexedEq);
		final List<byte[]> keys = new ArrayList<>();
		forEachSnapshotKey(store, filter, keys::add);
		if (heaviness.isDistributedHeavy(store.shardCount())) {
			final List<byte[]> mapped = DistributedKeyFanOut.mapReduceByShard(
					keys,
					store,
					chunk -> residualBlobs(store, schema, filter, chunk),
					heaviness);
			blobs.addAll(mapped);
			return;
		}
		if (heaviness.isHeavy()) {
			final List<byte[]> mapped = AdaptiveParallelScan.mapMergeKeys(keys, chunk ->
					residualBlobs(store, schema, filter, chunk), heaviness);
			blobs.addAll(mapped);
			return;
		}
		blobs.addAll(residualBlobs(store, schema, filter, keys));
	}

	static List<byte[]> residualBlobs(
			TableStore store,
			TableSchema schema,
			FilterCondition filter,
			List<byte[]> keys
	) {
		if (keys == null || keys.isEmpty()) {
			return List.of();
		}
		final List<byte[]> values = new ArrayList<>(keys.size());
		for (byte[] key : keys) {
			final byte[] value = store.getCommittedBytes(key);
			if (value != null) {
				values.add(value);
			}
		}
		return WireResidualBatch.filterBlobs(values, schema, filter);
	}

	static boolean isIndexedEqSnapshot(TableStore store, FilterCondition filter) {
		if (!(filter instanceof LogicalOperatorCondition loc)) {
			return false;
		}
		if (loc.getOperator() != LogicalOperatorCondition.Operator.EQ
				|| loc.getValues() == null
				|| loc.getValues().length == 0) {
			return false;
		}
		return store.hasEqIndex(List.of(loc.getField()));
	}

	/**
	 * Candidate keys for snapshot: indexed EQ when possible, else PK stream + residual match by caller.
	 */
	static void forEachSnapshotKey(TableStore store, FilterCondition filter, Consumer<byte[]> consumer) {
		if (isIndexedEqSnapshot(store, filter)) {
			final LogicalOperatorCondition loc = (LogicalOperatorCondition) filter;
			final byte[] wire = SqlWireUtil.toGenericArray(loc.getValues()[0]);
			store.forEachEqKey(List.of(loc.getField()), new byte[][]{wire}, consumer);
			return;
		}
		store.forEachPrimaryKey(consumer);
	}
}
