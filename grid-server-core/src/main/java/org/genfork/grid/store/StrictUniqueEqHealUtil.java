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
import java.util.List;
import java.util.Objects;

import org.genfork.grid.catalog.ColumnListEqualUtil;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.replication.ReplicationCoordinator;

/**
 * STRICT UNIQUE EQ choke: ghost sealed postings → one-shot seal dump heal + re-probe.
 * <p>
 * Happy-path live hits never dump ({@code O(EQ postings)} — STRICT UNIQUE is typically
 * 0–1 pointer; not {@code O(table)}). Heal invokes domain {@code dumpDomainSealed} once
 * (cost of a seal dump for that domain) and is skipped when there is no ghost posting.
 * When heal is deferred, {@link #failClosedIfGhostUnhealed} refuses DML that would invent
 * a second IDENTITY row.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class StrictUniqueEqHealUtil {
	static final String MSG_STALE_STRICT_UNIQUE =
			"STRICT UNIQUE index stale or seal heal deferred domain=";

	private StrictUniqueEqHealUtil() {
	}

	/**
	 * Live PK keys for EQ on {@code columns}; heals once when STRICT UNIQUE EQ is ghost-only.
	 */
	public static List<byte[]> liveEqKeys(
			TableStore store,
			List<String> columns,
			byte[][] wireValues
	) {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(columns, "columns");
		Objects.requireNonNull(wireValues, "wireValues");
		List<byte[]> raw = store.rawIndexEqKeys(columns, wireValues);
		List<byte[]> live = filterLive(store, raw);
		if (!coversStrictUnique(store.schema(), columns) || !hasGhostPostings(store, raw)) {
			return live;
		}
		tryHealSealedIndexes(store);
		raw = store.rawIndexEqKeys(columns, wireValues);
		return filterLive(store, raw);
	}

	/**
	 * Stream live EQ hits (same heal policy as {@link #liveEqKeys}).
	 */
	public static void forEachLiveEqKey(
			TableStore store,
			List<String> columns,
			byte[][] wireValues,
			java.util.function.Consumer<byte[]> consumer
	) {
		Objects.requireNonNull(consumer, "consumer");
		final List<byte[]> live = liveEqKeys(store, columns, wireValues);
		for (int i = 0, n = live.size(); i < n; i++) {
			consumer.accept(live.get(i));
		}
	}

	/**
	 * Fail-closed for INSERT / ON CONFLICT miss when ghost UNIQUE EQ cannot be healed
	 * (dump deferred). After a successful heal, leftover ghosts mean true absence (deleted
	 * sealed posting) — re-INSERT is allowed.
	 */
	public static void failClosedIfGhostUnhealed(
			TableStore store,
			List<String> columns,
			byte[][] wireValues
	) {
		if (!coversStrictUnique(store.schema(), columns)) {
			return;
		}
		final List<byte[]> raw = store.rawIndexEqKeys(columns, wireValues);
		if (!isGhostOnly(store, raw)) {
			return;
		}
		final boolean healed = tryHealSealedIndexes(store);
		final List<byte[]> after = store.rawIndexEqKeys(columns, wireValues);
		if (!healed && (isGhostOnly(store, after) || (!after.isEmpty() && filterLive(store, after).isEmpty()))) {
			throw new IllegalStateException(MSG_STALE_STRICT_UNIQUE + store.schema().catalogKey());
		}
	}

	/**
	 * {@code true} when {@code columns} match a STRICT secondary unique index (order-sensitive).
	 */
	public static boolean coversStrictUnique(TableSchema schema, List<String> columns) {
		if (schema == null || columns == null || columns.isEmpty()) {
			return false;
		}
		final List<IndexDef> indexes = schema.indexes();
		for (int i = 0, n = indexes.size(); i < n; i++) {
			final IndexDef idx = indexes.get(i);
			if (idx.kind() != IndexType.STRICT) {
				continue;
			}
			if (ColumnListEqualUtil.columnsEqualIgnoreCaseOrdered(idx.columns(), columns)) {
				return true;
			}
		}
		return false;
	}

	/** At least one EQ posting whose PK is not live in the map. */
	static boolean hasGhostPostings(TableStore store, List<byte[]> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return false;
		}
		for (int i = 0, n = candidates.size(); i < n; i++) {
			final byte[] candidate = candidates.get(i);
			if (candidate != null && store.getCommittedBytes(candidate) == null) {
				return true;
			}
		}
		return false;
	}

	/** Every EQ posting is non-live (true ghost-only; no live owner). */
	static boolean isGhostOnly(TableStore store, List<byte[]> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return false;
		}
		boolean any = false;
		for (int i = 0, n = candidates.size(); i < n; i++) {
			final byte[] candidate = candidates.get(i);
			if (candidate == null) {
				continue;
			}
			any = true;
			if (store.getCommittedBytes(candidate) != null) {
				return false;
			}
		}
		return any;
	}

	static List<byte[]> filterLive(TableStore store, List<byte[]> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		final List<byte[]> live = new ArrayList<>(candidates.size());
		for (int i = 0, n = candidates.size(); i < n; i++) {
			final byte[] candidate = candidates.get(i);
			if (candidate != null && store.getCommittedBytes(candidate) != null) {
				live.add(candidate);
			}
		}
		return live;
	}

	/**
	 * @return {@code true} when heal ran without deferred shards (or durability unbound)
	 */
	static boolean tryHealSealedIndexes(TableStore store) {
		final ReplicationCoordinator coord = store.replicationCoordinatorOrNull();
		if (coord == null) {
			return true;
		}
		return coord.tryHealSealedDomainIndexes(store.schema().catalogKey());
	}

}
