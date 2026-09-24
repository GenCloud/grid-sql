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
package org.genfork.grid.replication.log;

import org.genfork.grid.replication.codec.ReplicationOp;

import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Orders OpLog deferred appends per {@code domain#shard} when pipelined ORCHID
 * futures complete out of seq order.
 * <p>
 * Holdback uses expected opSeq of in-flight proposes (contiguous proposer chain).
 * Inflight slots are <em>refcounted</em>: the same seq may be registered twice after
 * propose-chain rewind + re-admit; a late {@code cancelJoin} from the failed TX must
 * not unblock a higher ready seq.
 * Ready ops drain as soon as no lower expected seq remains in flight — safe under
 * continuous write load (unlike a global joining-count barrier).
 * Waiters park via {@link LockSupport} (VT-safe; no {@code Object.wait}).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class StreamOpLogAppender {
	private static final long PARK_SLICE_NS = 100_000L;

	private final OpLog opLog;
	private final ConcurrentHashMap<String, StreamState> streams = new ConcurrentHashMap<>();

	public StreamOpLogAppender(OpLog opLog) {
		this.opLog = opLog;
	}

	private static String key(String domainType, int shard) {
		return domainType + "#" + shard;
	}

	/**
	 * Register an in-flight propose that will commit as {@code expectedOpSeq}
	 * (orchid {@code prevOpSeq + 1}). Refcounted — safe under re-admit of the same seq.
	 */
	public void beginJoin(String domainType, int shard, long expectedOpSeq) {
		if (expectedOpSeq <= 0L) {
			return;
		}
		final StreamState state = state(domainType, shard);
		synchronized (state.readyLock) {
			InflightSeqRefCounts.increment(state.inflightExpected, expectedOpSeq);
		}
	}

	/** Call when propose wait fails before {@link #publishCommitted}. Decrements one join. */
	public void cancelJoin(String domainType, int shard, long expectedOpSeq) {
		if (expectedOpSeq <= 0L) {
			return;
		}
		final StreamState state = state(domainType, shard);
		synchronized (state.readyLock) {
			InflightSeqRefCounts.decrement(state.inflightExpected, expectedOpSeq);
			drainLocked(domainType, shard, state);
		}
		unparkWaiters(state);
	}

	/**
	 * Buffer a committed op and park until it is appended.
	 * Prefer {@link #publishBatch} when one thread joins several proposes.
	 */
	public void publishCommitted(ReplicationOp committed) {
		publishBatch(List.of(committed));
	}

	/**
	 * Buffer many committed ops from one joiner batch, drain contiguous holdback,
	 * then park until every op is appended.
	 */
	public void publishBatch(List<ReplicationOp> committedOps) {
		if (committedOps == null || committedOps.isEmpty()) {
			return;
		}
		final ReplicationOp first = committedOps.get(0);
		final String domainType = first.domainType();
		final int shard = first.shard();
		final StreamState state = state(domainType, shard);
		synchronized (state.readyLock) {
			for (ReplicationOp op : committedOps) {
				InflightSeqRefCounts.clear(state.inflightExpected, op.opSeq());
				state.ready.put(op.opSeq(), op);
			}
			drainLocked(domainType, shard, state);
		}
		unparkWaiters(state);
		for (ReplicationOp op : committedOps) {
			awaitAppended(domainType, shard, op.opSeq(), state);
		}
	}

	private void awaitAppended(String domainType, int shard, long seq, StreamState state) {
		for (;;) {
			if (isAppended(domainType, shard, seq)) {
				return;
			}
			final Thread self = Thread.currentThread();
			state.waiters.offer(self);
			try {
				if (!isAppended(domainType, shard, seq)) {
					LockSupport.parkNanos(state, PARK_SLICE_NS);
				}
			} finally {
				state.waiters.remove(self);
			}
			if (Thread.interrupted()) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
						"Interrupted waiting for ordered OpLog append seq=" + seq);
			}
			synchronized (state.readyLock) {
				drainLocked(domainType, shard, state);
			}
			unparkWaiters(state);
		}
	}

	public void force(String domainType, int shard) {
		opLog.force(domainType, shard);
	}

	private boolean isAppended(String domainType, int shard, long seq) {
		return opLog.containsSeq(domainType, shard, seq);
	}

	private StreamState state(String domainType, int shard) {
		return streams.computeIfAbsent(key(domainType, shard), ignored -> new StreamState());
	}

	/**
	 * Append ready ops in ascending seq while no in-flight propose expects a lower seq
	 * (gap that must land first). Global orchid seq need not be dense on one stream —
	 * only same-stream inflight below {@code min} blocks.
	 */
	private void drainLocked(String domainType, int shard, StreamState state) {
		while (!state.ready.isEmpty()) {
			final long min = state.ready.firstKey();
			if (opLog.containsSeq(domainType, shard, min)) {
				state.ready.remove(min);
				continue;
			}
			final Long lowestInflight = InflightSeqRefCounts.lowest(state.inflightExpected);
			if (lowestInflight != null && lowestInflight < min) {
				break;
			}
			final ReplicationOp op = state.ready.remove(min);
			opLog.appendDeferred(op);
		}
	}

	private static void unparkWaiters(StreamState state) {
		Thread waiter;
		while ((waiter = state.waiters.poll()) != null) {
			LockSupport.unpark(waiter);
		}
	}

	private static final class StreamState {
		private final Object readyLock = new Object();
		private final TreeMap<Long, ReplicationOp> ready = new TreeMap<>();
		/** expectedOpSeq → join refcount (failProposeChain re-admit safe). */
		private final TreeMap<Long, Integer> inflightExpected = new TreeMap<>();
		private final ConcurrentLinkedQueue<Thread> waiters = new ConcurrentLinkedQueue<>();
	}
}
