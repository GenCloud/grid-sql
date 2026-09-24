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

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Per-SELECT pinned read horizon for open-TX distributed fan-out (TD-SQL-001).
 * <p>
 * Fan-out path pins an epoch before invoking peer key / blob / tombstone suppliers and clears
 * it in {@code finally}. Snapshot-aware suppliers read {@link #currentOrZero()} on the same
 * logic thread — never hold the pin across Netty wait.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class DistTxSnapshot {

	private static final ThreadLocal<Long> PINNED = new ThreadLocal<>();

	private DistTxSnapshot() {
	}

	public static void pin(long epoch) {
		PINNED.set(epoch);
	}

	public static void clear() {
		PINNED.remove();
	}

	/**
	 * Pinned epoch for the current fan-out, or {@code 0} when none.
	 * Peer suppliers must call this (or implement {@link SnapshotAwareKeySupplier} /
	 * {@link SnapshotAwareBlobFetcher}) so key / blob / tombstone fetches share one horizon.
	 */
	public static long currentOrZero() {
		final Long v = PINNED.get();
		return v == null ? 0L : v;
	}

	/**
	 * Peer key / tombstone supplier that receives the pinned snapshot epoch.
	 * Plain {@link Function} adapters still work; prefer this for horizon-aware transports.
	 */
	@FunctionalInterface
	public interface SnapshotAwareKeySupplier extends Function<String, List<byte[]>> {
		List<byte[]> supply(String sqlOrTable, long snapshotEpoch);

		@Override
		default List<byte[]> apply(String sqlOrTable) {
			return supply(sqlOrTable, currentOrZero());
		}
	}

	/**
	 * Peer row-blob fetcher that receives the pinned snapshot epoch.
	 */
	@FunctionalInterface
	public interface SnapshotAwareBlobFetcher extends BiFunction<String, byte[], byte[]> {
		byte[] fetch(String table, byte[] key, long snapshotEpoch);

		@Override
		default byte[] apply(String table, byte[] key) {
			return fetch(table, key, currentOrZero());
		}
	}
}
