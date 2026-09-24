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
package org.genfork.grid.query.adaptive;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.query.util.QueryChunkMerge;
import org.genfork.grid.threading.GridFutures;
import org.genfork.grid.threading.ThreadService;
import org.genfork.grid.utils.ArrayUtil;

/**
 * Adaptive parallel scan over wire {@code byte[]} key / blob chunks (AQE v1 + v2 mid-flight).
 * <p>
 * Activates only when {@link QueryHeaviness#isHeavy()} and
 * {@link HeavyQueryAdmission#tryAcquire()} succeed; otherwise maps serially.
 * Mid-flight {@link AdaptiveChunkScheduler} may re-split or coalesce unfinished
 * work under admission pressure. Chunk fan-out uses
 * {@link ThreadService#getCpuExecutor()} (bounded CPU pool); merge reuses
 * {@link QueryChunkMerge}. Mid-pipeline keys stay wire bytes — no {@code Object} decode.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class AdaptiveParallelScan {
	private static final int MIN_PARALLEL_CHUNKS = 2;
	private static final int SINGLE_CHUNK = 1;
	private static final int MERGE_LIMIT_UNLIMITED = 0;
	private static final int EMPTY_SIZE = 0;
	private static final int SHARD_BUCKET_OFFSET = 0;
	/** First wave processes this fraction of chunks before mid-flight resize. */
	private static final int FIRST_WAVE_NUM = 1;
	private static final int FIRST_WAVE_DEN = 2;

	private AdaptiveParallelScan() {
	}

	/**
	 * Map wire-key chunks then UNION-all merge. Parallel only when heavy + admitted.
	 *
	 * @param keys   wire keys (or blobs); null treated as empty
	 * @param mapper chunk mapper (identity-preserving transform on wire bytes)
	 * @param h      predicted heaviness
	 */
	public static List<byte[]> mapMergeKeys(
			List<byte[]> keys,
			Function<List<byte[]>, List<byte[]>> mapper,
			QueryHeaviness h
	) {
		return mapMergeKeys(keys, mapper, h, HeavyQueryAdmission.global());
	}

	/**
	 * Same as {@link #mapMergeKeys(List, Function, QueryHeaviness)} with explicit admission.
	 */
	public static List<byte[]> mapMergeKeys(
			List<byte[]> keys,
			Function<List<byte[]>, List<byte[]>> mapper,
			QueryHeaviness h,
			HeavyQueryAdmission admission
	) {
		if (mapper == null) {
			throw new IllegalArgumentException("mapper is required");
		}
		final List<byte[]> input = keys == null ? List.of() : keys;
		if (input.isEmpty()) {
			return List.of();
		}
		if (h == null || !h.isHeavy() || admission == null || !admission.tryAcquire()) {
			return serialMap(input, mapper);
		}
		try {
			final int workers = resolveWorkerCount(input.size(), admission.maxWorkersPerHeavy());
			if (workers < MIN_PARALLEL_CHUNKS) {
				return serialMap(input, mapper);
			}
			List<List<byte[]>> chunks = splitByShardRange(input, workers);
			if (AdaptiveChunkScheduler.shouldCoalesce(chunks.size(), workers)) {
				chunks = AdaptiveChunkScheduler.coalesce(chunks, workers);
				DistributedQueryMetrics.recordAqeCoalesce();
			}
			final List<List<byte[]>> mapped = mapChunksWithMidFlight(chunks, mapper, workers);
			final List<byte[]> merged = QueryChunkMerge.merge(mapped, MERGE_LIMIT_UNLIMITED);
			DistributedQueryMetrics.recordMapReduce(mapped.size(), merged.size());
			return merged;
		} finally {
			admission.release();
		}
	}

	private static List<byte[]> serialMap(
			List<byte[]> input,
			Function<List<byte[]>, List<byte[]>> mapper
	) {
		final List<byte[]> out = mapper.apply(input);
		if (out == null) {
			return List.of();
		}
		return out;
	}

	private static int resolveWorkerCount(int keyCount, int maxWorkers) {
		final int capped = Math.min(maxWorkers, keyCount);
		return Math.max(EMPTY_SIZE, capped);
	}

	/**
	 * Split keys into {@code parts} hash-shard buckets (range within each shard list).
	 * Empty buckets are dropped so merge order follows non-empty shard order.
	 */
	static List<List<byte[]>> splitByShardRange(List<byte[]> keys, int parts) {
		final int n = Math.min(parts, keys.size());
		if (n <= SINGLE_CHUNK) {
			return List.of(keys);
		}
		final List<List<byte[]>> buckets = new ArrayList<>(n);
		for (int i = SHARD_BUCKET_OFFSET; i < n; i++) {
			buckets.add(new ArrayList<>());
		}
		for (byte[] key : keys) {
			final int shard = shardOf(key, n);
			buckets.get(shard).add(key);
		}
		final List<List<byte[]>> nonEmpty = new ArrayList<>(n);
		for (List<byte[]> bucket : buckets) {
			if (!bucket.isEmpty()) {
				nonEmpty.add(bucket);
			}
		}
		if (nonEmpty.isEmpty()) {
			return List.of(keys);
		}
		return nonEmpty;
	}

	private static int shardOf(byte[] key, int parts) {
		if (key == null || parts <= SINGLE_CHUNK) {
			return SHARD_BUCKET_OFFSET;
		}
		final int hash = ArrayUtil.fastHash(key);
		return Math.floorMod(hash, parts);
	}

	/**
	 * First wave maps half the chunks; remaining may be re-split / coalesced then mapped.
	 */
	private static List<List<byte[]>> mapChunksWithMidFlight(
			List<List<byte[]>> chunks,
			Function<List<byte[]>, List<byte[]>> mapper,
			int workers
	) {
		if (chunks.size() < MIN_PARALLEL_CHUNKS) {
			return mapChunksParallel(chunks, mapper);
		}
		final int firstWave = Math.max(SINGLE_CHUNK, (chunks.size() * FIRST_WAVE_NUM) / FIRST_WAVE_DEN);
		final List<List<byte[]>> wave1 = chunks.subList(0, firstWave);
		final List<List<byte[]>> rest = new ArrayList<>(chunks.subList(firstWave, chunks.size()));
		final List<List<byte[]>> mapped = new ArrayList<>(chunks.size());
		mapped.addAll(mapChunksParallel(wave1, mapper));

		List<List<byte[]>> remaining = rest;
		final int remainingKeys = countKeys(remaining);
		if (AdaptiveChunkScheduler.shouldResplit(remainingKeys, remaining.size(), workers)) {
			final List<byte[]> flat = flatten(remaining);
			remaining = AdaptiveChunkScheduler.resplit(flat, workers);
			DistributedQueryMetrics.recordAqeResplit();
		} else if (AdaptiveChunkScheduler.shouldCoalesce(remaining.size(), workers)) {
			remaining = AdaptiveChunkScheduler.coalesce(remaining, workers);
			DistributedQueryMetrics.recordAqeCoalesce();
		}
		if (!remaining.isEmpty()) {
			mapped.addAll(mapChunksParallel(remaining, mapper));
		}
		return mapped;
	}

	private static int countKeys(List<List<byte[]>> chunks) {
		int total = EMPTY_SIZE;
		for (List<byte[]> chunk : chunks) {
			if (chunk != null) {
				total += chunk.size();
			}
		}
		return total;
	}

	private static List<byte[]> flatten(List<List<byte[]>> chunks) {
		final List<byte[]> flat = new ArrayList<>();
		for (List<byte[]> chunk : chunks) {
			if (chunk != null && !chunk.isEmpty()) {
				flat.addAll(chunk);
			}
		}
		return flat;
	}

	private static List<List<byte[]>> mapChunksParallel(
			List<List<byte[]>> chunks,
			Function<List<byte[]>, List<byte[]>> mapper
	) {
		final ExecutorService cpu = ThreadService.getCpuExecutor();
		final int size = chunks.size();
		final List<CompletableFuture<List<byte[]>>> futures = new ArrayList<>(size);
		for (List<byte[]> chunk : chunks) {
			final List<byte[]> captured = chunk;
			futures.add(GridFutures.supplyAsync(() -> {
				final List<byte[]> mapped = mapper.apply(captured);
				return mapped == null ? List.of() : mapped;
			}, cpu));
		}
		final List<List<byte[]>> mapped = new ArrayList<>(size);
		try {
			for (CompletableFuture<List<byte[]>> future : futures) {
				mapped.add(future.join());
			}
		} catch (CompletionException ex) {
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("adaptive parallel scan failed", cause);
		}
		return mapped;
	}
}
