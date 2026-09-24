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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.query.util.QueryChunkMerge;
import org.genfork.grid.threading.GridFutures;
import org.genfork.grid.threading.ThreadService;

/**
 * Distributed SELECT fan-out: scatter to local + peer suppliers, merge UNION-all.
 * <p>
 * Deeper than merge-v1: optionally pushes {@code LIMIT} into shard SQL when the caller
 * knows (from ANTLR) that the statement has no LIMIT clause, then early-stops remaining
 * peers once the merged cap is satisfied. Chunk merge is {@link QueryChunkMerge}.
 * Map-reduce stages ({@link #executeMapReduceStages}) run independent wire-chunk
 * producers on {@link ThreadService#getCpuExecutor()} and reduce via the same merge.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class DistributedQueryExecutor {
	/** Prefix appended when pushing a shard {@code LIMIT} bound. */
	public static final String LIMIT_CLAUSE_PREFIX = " LIMIT ";

	private static final int MIN_PARALLEL_STAGES = 2;

	private DistributedQueryExecutor() {
	}

	/**
	 * Scatter {@code sql} to local + peers; when {@code pushLimit && limit > 0}, append
	 * {@link #LIMIT_CLAUSE_PREFIX}{@code limit} (caller owns ANTLR {@code limitOrNull == null}).
	 */
	public static List<byte[]> execute(
			String sql,
			int limit,
			boolean pushLimit,
			Function<String, List<byte[]>> localExecute,
			List<Function<String, List<byte[]>>> peerExecutors) {
		final int cap = Math.max(0, limit);
		final List<List<byte[]>> chunks = new ArrayList<>();
		int collected = 0;

		if (localExecute != null) {
			final String shardSql = shardSql(sql, pushLimit, remainingLimit(cap, collected));
			final List<byte[]> local = localExecute.apply(shardSql);
			chunks.add(local);
			collected = countRows(chunks);
			if (cap > 0 && collected >= cap) {
				return finishFanIn(chunks, cap);
			}
		}

		if (peerExecutors != null) {
			for (Function<String, List<byte[]>> peer : peerExecutors) {
				if (peer == null) {
					continue;
				}
				final String shardSql = shardSql(sql, pushLimit, remainingLimit(cap, collected));
				chunks.add(peer.apply(shardSql));
				collected = countRows(chunks);
				if (cap > 0 && collected >= cap) {
					return finishFanIn(chunks, cap);
				}
			}
		}
		return finishFanIn(chunks, cap);
	}

	/**
	 * Map independent stage suppliers (wire {@code List<byte[]>} chunks) then reduce
	 * via {@link QueryChunkMerge#merge(List, int)}. Stages run in parallel on
	 * {@link ThreadService#getCpuExecutor()} when count ≥ 2; otherwise serial.
	 *
	 * @param stageMappers ordered stage producers; null entries skipped
	 * @param limit        max merged rows; {@code <= 0} = no cap
	 */
	public static List<byte[]> executeMapReduceStages(
			List<Supplier<List<byte[]>>> stageMappers,
			int limit
	) {
		final int cap = Math.max(0, limit);
		if (stageMappers == null || stageMappers.isEmpty()) {
			return finishMapReduce(List.of(), cap);
		}
		final List<Supplier<List<byte[]>>> stages = new ArrayList<>(stageMappers.size());
		for (Supplier<List<byte[]>> stage : stageMappers) {
			if (stage != null) {
				stages.add(stage);
			}
		}
		if (stages.isEmpty()) {
			return finishMapReduce(List.of(), cap);
		}
		final List<List<byte[]>> chunks;
		if (stages.size() < MIN_PARALLEL_STAGES) {
			chunks = mapStagesSerial(stages);
		} else {
			chunks = mapStagesParallel(stages);
		}
		return finishMapReduce(chunks, cap);
	}

	/**
	 * Peer-style map-reduce: each stage function receives {@code sql} and returns a
	 * wire chunk; reduce via {@link QueryChunkMerge}.
	 *
	 * @param sql            shared input (shard SQL or plan token)
	 * @param stageExecutors ordered map functions (local/peers); null entries skipped
	 * @param limit          max merged rows; {@code <= 0} = no cap
	 */
	public static List<byte[]> executeMapReduceStages(
			String sql,
			List<Function<String, List<byte[]>>> stageExecutors,
			int limit
	) {
		if (stageExecutors == null || stageExecutors.isEmpty()) {
			return finishMapReduce(List.of(), Math.max(0, limit));
		}
		final List<Supplier<List<byte[]>>> suppliers = new ArrayList<>(stageExecutors.size());
		for (Function<String, List<byte[]>> stage : stageExecutors) {
			if (stage == null) {
				continue;
			}
			final Function<String, List<byte[]>> captured = stage;
			suppliers.add(() -> {
				final List<byte[]> chunk = captured.apply(sql);
				return chunk == null ? List.of() : chunk;
			});
		}
		return executeMapReduceStages(suppliers, limit);
	}

	/**
	 * Append {@code LIMIT n} when {@code limit > 0}. Caller must set push only when ANTLR
	 * reported no LIMIT clause (avoids double LIMIT).
	 */
	public static String withPushedLimit(String sql, int limit) {
		if (sql == null || limit <= 0) {
			return sql;
		}
		final String trimmed = sql.trim();
		if (trimmed.isEmpty()) {
			return trimmed;
		}
		return trimmed + LIMIT_CLAUSE_PREFIX + limit;
	}

	/** Delegates to {@link QueryChunkMerge#merge(List, int)} (shared with SQL UNION). */
	public static List<byte[]> merge(List<List<byte[]>> chunks, int limit) {
		return QueryChunkMerge.merge(chunks, limit);
	}

	private static List<List<byte[]>> mapStagesSerial(List<Supplier<List<byte[]>>> stages) {
		final List<List<byte[]>> chunks = new ArrayList<>(stages.size());
		for (Supplier<List<byte[]>> stage : stages) {
			final List<byte[]> chunk = stage.get();
			chunks.add(chunk == null ? List.of() : chunk);
		}
		return chunks;
	}

	private static List<List<byte[]>> mapStagesParallel(List<Supplier<List<byte[]>>> stages) {
		final ExecutorService cpu = ThreadService.getCpuExecutor();
		final List<CompletableFuture<List<byte[]>>> futures = new ArrayList<>(stages.size());
		for (Supplier<List<byte[]>> stage : stages) {
			final Supplier<List<byte[]>> captured = stage;
			futures.add(GridFutures.supplyAsync(() -> {
				final List<byte[]> chunk = captured.get();
				return chunk == null ? List.of() : chunk;
			}, cpu));
		}
		final List<List<byte[]>> chunks = new ArrayList<>(futures.size());
		try {
			for (CompletableFuture<List<byte[]>> future : futures) {
				chunks.add(future.join());
			}
		} catch (CompletionException ex) {
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("distributed map-reduce stage failed", cause);
		}
		return chunks;
	}

	private static List<byte[]> finishFanIn(List<List<byte[]>> chunks, int limit) {
		final List<byte[]> merged = merge(chunks, limit);
		DistributedQueryMetrics.recordFanIn(chunks.size(), merged.size());
		return merged;
	}

	private static List<byte[]> finishMapReduce(List<List<byte[]>> chunks, int limit) {
		final List<byte[]> merged = merge(chunks, limit);
		final int stageCount = chunks.size();
		DistributedQueryMetrics.recordMapReduce(stageCount, merged.size());
		DistributedQueryMetrics.recordFanIn(stageCount, merged.size());
		return merged;
	}

	private static String shardSql(String sql, boolean pushLimit, int remaining) {
		if (!pushLimit) {
			return sql;
		}
		return withPushedLimit(sql, remaining);
	}

	private static int remainingLimit(int cap, int collected) {
		if (cap <= 0) {
			return 0;
		}
		return Math.max(1, cap - collected);
	}

	private static int countRows(List<List<byte[]>> chunks) {
		int total = 0;
		for (List<byte[]> chunk : chunks) {
			if (chunk != null) {
				total += chunk.size();
			}
		}
		return total;
	}
}
