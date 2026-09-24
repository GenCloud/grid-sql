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
package org.genfork.grid.query.distributed;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.sql.exec.DistTxSnapshot;
import org.genfork.grid.sql.tx.KeyWrapper;
import org.genfork.grid.store.TableStore;

/**
 * Key / blob resolution for distributed SELECT / JOIN.
 * <p>
 * Committed key discovery is local index plus optional peer key lists for remote-only
 * rows (not identical-SQL MapReduce — TD-QUERY-002). Partitioned compute uses
 * {@link ShardPartitionPlanner#mapReduceByShard} (domain shards).
 * Open-TX remote dirty keys merge in {@code SqlQueryExecutor} (not here).
 * <p>
 * <b>TX barrier:</b> committed peers/local only; dirty overlay is caller-side.
 * Open-TX callers pin {@link DistTxSnapshot} so peer suppliers see
 * {@link DistTxSnapshot#currentOrZero()}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class DistributedKeyFanOut {
	private static final int FAN_IN_SET_MIN_CAPACITY = 16;
	private static final int MERGE_LIMIT_UNLIMITED = 0;

	private DistributedKeyFanOut() {
	}

	/**
	 * Resolve committed keys from the local index path, then merge peer key discoveries.
	 * <p>
	 * Peers are consulted for <em>key lists</em> (remote-only rows / miss fill), not as
	 * identical-SQL MapReduce (TD-QUERY-002). Open-TX callers pin {@link DistTxSnapshot}
	 * so suppliers see {@link DistTxSnapshot#currentOrZero()}.
	 */
	public static List<byte[]> fanOutKeys(
			String sql,
			int limit,
			boolean pushLimit,
			TableStore store,
			List<Function<String, List<byte[]>>> peerKeyExecutors
	) {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(sql, "sql");
		final List<byte[]> local = localKeys(store, sql);
		if (peerKeyExecutors == null || peerKeyExecutors.isEmpty()) {
			return applyLimit(local, limit);
		}
		// Local already fills LIMIT — skip peer key discovery (product early-stop).
		if (limit > 0 && local.size() >= limit) {
			return applyLimit(local, limit);
		}
		final Set<KeyWrapper> seen = new LinkedHashSet<>(
				Math.max(FAN_IN_SET_MIN_CAPACITY, local.size() * 2));
		final List<byte[]> merged = new ArrayList<>(local.size());
		for (byte[] key : local) {
			if (key != null && seen.add(new KeyWrapper(key))) {
				merged.add(key);
			}
		}
		int peerSources = 0;
		int peerAdded = 0;
		for (Function<String, List<byte[]>> peer : peerKeyExecutors) {
			if (peer == null) {
				continue;
			}
			peerSources++;
			// Horizon watermark for SnapshotAware / ThreadLocal peer transports.
			DistTxSnapshot.currentOrZero();
			final List<byte[]> peerKeys = peer.apply(sql);
			if (peerKeys == null || peerKeys.isEmpty()) {
				continue;
			}
			for (byte[] key : peerKeys) {
				if (key == null || key.length == 0) {
					continue;
				}
				if (seen.add(new KeyWrapper(key))) {
					merged.add(key);
					peerAdded++;
				}
			}
		}
		if (peerSources > 0) {
			DistributedQueryMetrics.recordFanIn(peerSources, peerAdded);
		}
		return applyLimit(merged, limit);
	}

	/**
	 * Partitioned map-reduce over domain shards (wire keys in, wire blobs/keys out).
	 */
	public static List<byte[]> mapReduceByShard(
			List<byte[]> keys,
			TableStore store,
			Function<List<byte[]>, List<byte[]>> shardMapper,
			QueryHeaviness heaviness
	) {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(shardMapper, "shardMapper");
		if (keys == null || keys.isEmpty()) {
			return List.of();
		}
		if (heaviness != null && heaviness.isDistributedHeavy(store.shardCount())) {
			return ShardPartitionPlanner.mapReduceByShard(
					keys, store, shardMapper, MERGE_LIMIT_UNLIMITED);
		}
		final List<byte[]> out = shardMapper.apply(keys);
		return out == null ? List.of() : out;
	}

	public static List<Object[]> keysThenProject(
			String sql,
			int limit,
			boolean pushLimit,
			TableStore store,
			List<String> projection,
			List<Function<String, List<byte[]>>> peerKeyExecutors
	) {
		return keysThenProject(sql, limit, pushLimit, store, projection, peerKeyExecutors, List.of());
	}

	public static List<Object[]> keysThenProject(
			String sql,
			int limit,
			boolean pushLimit,
			TableStore store,
			List<String> projection,
			List<Function<String, List<byte[]>>> peerKeyExecutors,
			List<BiFunction<String, byte[], byte[]>> peerBlobFetchers
	) {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(sql, "sql");
		final List<byte[]> keys = fanOutKeys(sql, limit, pushLimit, store, peerKeyExecutors);
		final List<Object[]> rows = new ArrayList<>(keys.size());
		for (byte[] key : keys) {
			if (key == null) {
				continue;
			}
			final byte[] value = resolveBlob(store.schema().tableName(), store, key, peerBlobFetchers);
			if (value == null) {
				continue;
			}
			rows.add(store.projectBytes(value, projection));
			if (limit > 0 && rows.size() >= limit) {
				break;
			}
		}
		return rows;
	}

	public static List<byte[]> fanInBuildBlobs(
			String tableName,
			TableStore store,
			List<Function<String, List<byte[]>>> peerKeyExecutors
	) {
		return fanInBuildBlobs(tableName, store, peerKeyExecutors, List.of());
	}

	public static List<byte[]> fanInBuildBlobs(
			String tableName,
			TableStore store,
			List<Function<String, List<byte[]>>> peerKeyExecutors,
			List<BiFunction<String, byte[], byte[]>> peerBlobFetchers
	) {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(tableName, "tableName");
		final ColumnDef pk = store.schema().pkColumn();
		if (pk == null) {
			throw new IllegalArgumentException(
					"distributed JOIN build requires PRIMARY KEY: " + tableName);
		}
		final String sql = "SELECT " + pk.name() + " FROM " + tableName;
		final List<byte[]> keys = fanOutKeys(sql, 0, false, store, peerKeyExecutors);
		final Set<KeyWrapper> seen = new LinkedHashSet<>(
				Math.max(FAN_IN_SET_MIN_CAPACITY, keys.size() * 2));
		final List<byte[]> blobs = new ArrayList<>(keys.size());
		for (byte[] key : keys) {
			if (key == null || !seen.add(new KeyWrapper(key))) {
				continue;
			}
			final byte[] value = resolveBlob(tableName, store, key, peerBlobFetchers);
			if (value == null) {
				continue;
			}
			blobs.add(value);
		}
		return blobs;
	}

	public static List<Object[]> fanInBuildRows(
			String tableName,
			TableStore store,
			List<Function<String, List<byte[]>>> peerKeyExecutors
	) {
		final List<byte[]> blobs = fanInBuildBlobs(tableName, store, peerKeyExecutors);
		final List<Object[]> rows = new ArrayList<>(blobs.size());
		for (byte[] value : blobs) {
			rows.add(store.projectBytes(value, List.of("*")));
		}
		return rows;
	}

	public static byte[] resolveBlob(
			String tableName,
			TableStore store,
			byte[] key,
			List<BiFunction<String, byte[], byte[]>> peerBlobFetchers
	) {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(key, "key");
		final byte[] local = store.getCommittedBytes(key);
		if (local != null) {
			return local;
		}
		if (peerBlobFetchers == null || peerBlobFetchers.isEmpty()) {
			return null;
		}
		final String table = tableName == null ? store.schema().tableName() : tableName;
		for (BiFunction<String, byte[], byte[]> fetcher : peerBlobFetchers) {
			if (fetcher == null) {
				continue;
			}
			DistTxSnapshot.currentOrZero();
			final byte[] remote = fetcher.apply(table, key);
			if (remote != null) {
				return remote;
			}
		}
		return null;
	}

	private static List<byte[]> localKeys(TableStore store, String sql) {
		return store.selectKeys(sql);
	}

	private static List<byte[]> applyLimit(List<byte[]> keys, int limit) {
		if (keys == null || keys.isEmpty() || limit <= 0 || keys.size() <= limit) {
			return keys == null ? List.of() : keys;
		}
		return List.copyOf(keys.subList(0, limit));
	}
}
