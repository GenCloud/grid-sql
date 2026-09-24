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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-stream OpLog ship buffer that drains only complete TX units
 * ({@link OpLogTxUnits#splitCompletePrefix}). Shared by same-DC and Cross-DC publishers.
 * <p>
 * Short {@code synchronized} only around deque mutate — never hold across Netty write.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class TxUnitShardBuffer {
	private final ArrayDeque<ReplicationOp> queue = new ArrayDeque<>();
	private final boolean trackOfferTime;
	private long firstOfferEpochMs;

	public TxUnitShardBuffer() {
		this(false);
	}

	public TxUnitShardBuffer(boolean trackOfferTime) {
		this.trackOfferTime = trackOfferTime;
	}

	public void offer(ReplicationOp op) {
		synchronized (this) {
			if (trackOfferTime && queue.isEmpty()) {
				firstOfferEpochMs = System.currentTimeMillis();
			}
			queue.offerLast(op);
		}
	}

	public int size() {
		synchronized (this) {
			return queue.size();
		}
	}

	public boolean shouldFlushByTime(long now, long maxWaitMs) {
		synchronized (this) {
			return trackOfferTime
					&& !queue.isEmpty()
					&& firstOfferEpochMs > 0
					&& now - firstOfferEpochMs >= maxWaitMs;
		}
	}

	/**
	 * Remove and return complete TX units only; incomplete open unit stays queued.
	 */
	public List<ReplicationOp> drainCompleteUnits() {
		synchronized (this) {
			if (queue.isEmpty()) {
				return List.of();
			}
			final List<ReplicationOp> snapshot = new ArrayList<>(queue);
			final OpLogTxUnits.Split split = OpLogTxUnits.splitCompletePrefix(snapshot);
			queue.clear();
			for (ReplicationOp hold : split.remainder()) {
				queue.offerLast(hold);
			}
			if (trackOfferTime) {
				if (queue.isEmpty()) {
					firstOfferEpochMs = 0L;
				} else if (!split.complete().isEmpty()) {
					firstOfferEpochMs = System.currentTimeMillis();
				}
			}
			return split.complete();
		}
	}
}