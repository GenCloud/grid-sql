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
package org.genfork.grid.sql.tx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tracks participating (table, shard) streams for a TX commit unit.
 * Phases: {@link #beginAll} / {@link #beginAllBatch} → ops → {@link #endAll} / {@link #endAllBatch}
 * (or {@link #abortBegun} on failure).
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class MultiShardCommitBarrier {
	private enum Phase {
		IDLE,
		BEGUN,
		OPS,
		ENDED
	}

	public record StreamKey(String table, int shard) {
	}

	private final Set<StreamKey> streams = new LinkedHashSet<>();
	private final Set<Integer> tableHashes = new LinkedHashSet<>();
	private final List<StreamKey> begun = new ArrayList<>();
	private Phase phase = Phase.IDLE;

	public void registerTable(int tableHash) {
		tableHashes.add(tableHash);
	}

	public void registerStream(String table, int shard) {
		Objects.requireNonNull(table, "table");
		streams.add(new StreamKey(table, shard));
		tableHashes.add(table.hashCode());
	}

	public Set<StreamKey> participatingStreams() {
		return Collections.unmodifiableSet(streams);
	}

	public Set<Integer> participatingTables() {
		return Collections.unmodifiableSet(tableHashes);
	}

	public boolean isMultiShard() {
		return streams.size() > 1 || tableHashes.size() > 1;
	}

	/**
	 * Emit TX_BEGIN on every participating stream; records which streams were begun.
	 */
	public void beginAll(Consumer<StreamKey> beginFn) {
		Objects.requireNonNull(beginFn, "beginFn");
		if (phase != Phase.IDLE) {
			throw new IllegalStateException("beginAll requires IDLE phase, was " + phase);
		}
		begun.clear();
		for (StreamKey sk : streams) {
			beginFn.accept(sk);
			begun.add(sk);
		}
		phase = Phase.BEGUN;
	}

	/**
	 * Emit TX_BEGIN for all streams in one batch callback (orchid tip confirm amortized).
	 */
	public void beginAllBatch(Consumer<List<StreamKey>> beginBatchFn) {
		Objects.requireNonNull(beginBatchFn, "beginBatchFn");
		if (phase != Phase.IDLE) {
			throw new IllegalStateException("beginAllBatch requires IDLE phase, was " + phase);
		}
		begun.clear();
		final List<StreamKey> snapshot = new ArrayList<>(streams);
		beginBatchFn.accept(snapshot);
		begun.addAll(snapshot);
		phase = Phase.BEGUN;
	}

	/** Mark that data ops are in progress (after beginAll). */
	public void markOps() {
		if (phase != Phase.BEGUN && phase != Phase.OPS) {
			throw new IllegalStateException("markOps requires BEGUN/OPS, was " + phase);
		}
		phase = Phase.OPS;
	}

	/**
	 * Emit TX_COMMIT on every participating stream after successful ops.
	 */
	public void endAll(Consumer<StreamKey> commitFn) {
		Objects.requireNonNull(commitFn, "commitFn");
		if (phase != Phase.BEGUN && phase != Phase.OPS) {
			throw new IllegalStateException("endAll requires BEGUN/OPS, was " + phase);
		}
		for (StreamKey sk : streams) {
			commitFn.accept(sk);
		}
		phase = Phase.ENDED;
		begun.clear();
	}

	/**
	 * Emit TX_COMMIT for all streams in one batch callback.
	 */
	public void endAllBatch(Consumer<List<StreamKey>> commitBatchFn) {
		Objects.requireNonNull(commitBatchFn, "commitBatchFn");
		if (phase != Phase.BEGUN && phase != Phase.OPS) {
			throw new IllegalStateException("endAllBatch requires BEGUN/OPS, was " + phase);
		}
		commitBatchFn.accept(new ArrayList<>(streams));
		phase = Phase.ENDED;
		begun.clear();
	}

	/**
	 * Emit TX_ABORT on streams that already received BEGIN.
	 */
	public void abortBegun(Consumer<StreamKey> abortFn) {
		Objects.requireNonNull(abortFn, "abortFn");
		for (StreamKey sk : begun) {
			abortFn.accept(sk);
		}
		begun.clear();
		phase = Phase.IDLE;
	}

	public void clear() {
		streams.clear();
		tableHashes.clear();
		begun.clear();
		phase = Phase.IDLE;
	}
}