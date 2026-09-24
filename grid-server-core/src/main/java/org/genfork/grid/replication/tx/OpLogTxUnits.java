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
package org.genfork.grid.replication.tx;

import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-closed TX unit helpers over OpLog ranges.
 * <p>
 * A unit is {@code TX_BEGIN} … ({@code UPSERT}/{@code DELETE})* … {@code TX_COMMIT}|{@code TX_ABORT}.
 * Incomplete open units must not be repaired, Cross-DC shipped, or migrated as committed truth.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class OpLogTxUnits {
	private OpLogTxUnits() {
	}

	public static boolean isTxMarker(ReplicationOpType type) {
		return type == ReplicationOpType.TX_BEGIN
				|| type == ReplicationOpType.TX_COMMIT
				|| type == ReplicationOpType.TX_ABORT;
	}

	public static boolean isTxMutation(ReplicationOpType type) {
		return type == ReplicationOpType.UPSERT || type == ReplicationOpType.DELETE;
	}

	/**
	 * Exclusive end index of the longest prefix that does not leave an open TX unit.
	 * Returns {@code 0} when the list starts with an incomplete unit and has no complete prefix.
	 */
	public static int endIndexOfCompletePrefix(List<ReplicationOp> ops) {
		if (ops == null || ops.isEmpty()) {
			return 0;
		}
		int depth = 0;
		int lastComplete = 0;
		for (int i = 0; i < ops.size(); i++) {
			final ReplicationOpType type = ops.get(i).type();
			if (type == ReplicationOpType.TX_BEGIN) {
				if (depth > 0) {
					// Nested BEGIN restarts unit; previous open prefix is incomplete — cut before it.
					return lastComplete;
				}
				depth = 1;
			} else if (type == ReplicationOpType.TX_COMMIT || type == ReplicationOpType.TX_ABORT) {
				if (depth > 0) {
					depth = 0;
					lastComplete = i + 1;
				} else {
					// Orphan commit/abort — treat as complete singleton.
					lastComplete = i + 1;
				}
			} else if (depth == 0) {
				lastComplete = i + 1;
			}
		}
		return lastComplete;
	}

	/** Trim to complete TX units (and non-TX ops outside units); may return empty. */
	public static List<ReplicationOp> trimToCompleteUnits(List<ReplicationOp> ops) {
		final int end = endIndexOfCompletePrefix(ops);
		if (end <= 0) {
			return List.of();
		}
		if (end >= ops.size()) {
			return List.copyOf(ops);
		}
		return List.copyOf(ops.subList(0, end));
	}

	public static boolean hasIncompleteOpenTx(List<ReplicationOp> ops) {
		if (ops == null || ops.isEmpty()) {
			return false;
		}
		return endIndexOfCompletePrefix(ops) < ops.size();
	}

	public static boolean startsWithOpenTx(List<ReplicationOp> ops) {
		return ops != null && !ops.isEmpty() && ops.getFirst().type() == ReplicationOpType.TX_BEGIN;
	}

	/**
	 * Split drained buffer into shippable complete prefix and remainder to re-queue.
	 */
	public static Split splitCompletePrefix(List<ReplicationOp> ops) {
		final int end = endIndexOfCompletePrefix(ops);
		if (end <= 0) {
			return new Split(List.of(), ops == null ? List.of() : List.copyOf(ops));
		}
		if (end >= ops.size()) {
			return new Split(List.copyOf(ops), List.of());
		}
		final List<ReplicationOp> ship = new ArrayList<>(ops.subList(0, end));
		final List<ReplicationOp> hold = new ArrayList<>(ops.subList(end, ops.size()));
		return new Split(List.copyOf(ship), List.copyOf(hold));
	}

	public record Split(List<ReplicationOp> complete, List<ReplicationOp> remainder) {
	}

	/**
	 * {@code true} when {@code seq} appears in {@code ops} and lies inside the complete-unit prefix
	 * (safe to serve as repair/catch-up truth). Missing seq or mid-open-unit → {@code false}.
	 */
	public static boolean isSeqInCompleteUnit(List<ReplicationOp> ops, long seq) {
		if (ops == null || ops.isEmpty()) {
			return false;
		}
		final int end = endIndexOfCompletePrefix(ops);
		for (int i = 0; i < end; i++) {
			if (ops.get(i).opSeq() == seq) {
				return true;
			}
		}
		return false;
	}
}
