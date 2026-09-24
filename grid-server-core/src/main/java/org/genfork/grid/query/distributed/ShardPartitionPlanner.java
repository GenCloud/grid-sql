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
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.store.TableStore;

/**
 * Partition wire keys by domain shard ({@code fastHash % shardCount}) for MapReduce stages.
 * <p>
 * One non-empty shard → one map stage; reduce via {@link DistributedQueryExecutor#executeMapReduceStages}.
 * Not identical-SQL peer fan-out.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class ShardPartitionPlanner {
	private static final int EMPTY = 0;
	private static final int SINGLE_SHARD = 1;

	private ShardPartitionPlanner() {
	}

	/**
	 * Bucket keys by {@link TableStore#shardOf(byte[])}, map each non-empty shard, merge.
	 *
	 * @param keys        wire keys (null → empty)
	 * @param store       domain shard source
	 * @param shardMapper map stage on one shard's key slice (wire in / wire out)
	 * @param limit       merge cap; {@code <= 0} unlimited
	 */
	public static List<byte[]> mapReduceByShard(
			List<byte[]> keys,
			TableStore store,
			Function<List<byte[]>, List<byte[]>> shardMapper,
			int limit
	) {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(shardMapper, "shardMapper");
		final List<byte[]> input = keys == null ? List.of() : keys;
		if (input.isEmpty()) {
			return List.of();
		}
		final List<List<byte[]>> buckets = partitionByDomainShard(input, store);
		if (buckets.size() <= SINGLE_SHARD) {
			final List<byte[]> mapped = shardMapper.apply(buckets.isEmpty() ? input : buckets.get(EMPTY));
			return mapped == null ? List.of() : mapped;
		}
		final List<Supplier<List<byte[]>>> stages = new ArrayList<>(buckets.size());
		for (List<byte[]> bucket : buckets) {
			final List<byte[]> captured = bucket;
			stages.add(() -> {
				final List<byte[]> mapped = shardMapper.apply(captured);
				return mapped == null ? List.of() : mapped;
			});
		}
		return DistributedQueryExecutor.executeMapReduceStages(stages, limit);
	}

	/**
	 * Split keys into non-empty domain-shard buckets (order = ascending shard id).
	 */
	@VisibleForTesting
	public static List<List<byte[]>> partitionByDomainShard(List<byte[]> keys, TableStore store) {
		Objects.requireNonNull(store, "store");
		final List<byte[]> input = keys == null ? List.of() : keys;
		final int shardCount = Math.max(SINGLE_SHARD, store.shardCount());
		final List<List<byte[]>> buckets = new ArrayList<>(shardCount);
		for (int i = EMPTY; i < shardCount; i++) {
			buckets.add(new ArrayList<>());
		}
		for (byte[] key : input) {
			if (key == null) {
				continue;
			}
			final int shard = store.shardOf(key);
			final int idx = Math.floorMod(shard, shardCount);
			buckets.get(idx).add(key);
		}
		final List<List<byte[]>> nonEmpty = new ArrayList<>(shardCount);
		for (List<byte[]> bucket : buckets) {
			if (!bucket.isEmpty()) {
				nonEmpty.add(bucket);
			}
		}
		return nonEmpty;
	}
}
