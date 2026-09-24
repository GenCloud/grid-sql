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
package org.genfork.grid.replication.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide duplex + replication network counters and stage latency samples.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class ReplicationMetrics {
	private static final int LATENCY_SAMPLES = 1024;

	private static final LongAdder OPLOG_PUSH_SENT = new LongAdder();
	private static final LongAdder OPLOG_PUSH_RECV = new LongAdder();
	private static final LongAdder APPLY_ACK_SENT = new LongAdder();
	private static final LongAdder APPLY_ACK_RECV = new LongAdder();
	private static final LongAdder ORCHID_RPC_SENT = new LongAdder();
	private static final LongAdder CONNECT_FAILURES = new LongAdder();
	private static final LongAdder DUPLEX_MISMATCH = new LongAdder();
	private static final LongAdder DUPLEX_REPAIR = new LongAdder();
	private static final LongAdder DUPLEX_UNCORRECTABLE = new LongAdder();
	private static final LongAdder APPLY_NACK_RECV = new LongAdder();
	private static final LongAdder MAP_GET_HIT = new LongAdder();
	private static final LongAdder MAP_GET_MISS = new LongAdder();
	private static final LongAdder SHIP_BACKPRESSURE = new LongAdder();
	/** Live count of non-expired overlay pins ({@code OverlayStore}). */
	private static final LongAdder OVERLAY_PINNED_KEYS = new LongAdder();
	/** Sealed mmap loads after RAM miss (adaptive disk-first pressure signal). */
	private static final LongAdder SEALED_MISSES = new LongAdder();
	/** Adaptive disk-first LOW/NORMAL/HIGH transitions. */
	private static final LongAdder ADAPTIVE_MODE_CHANGES = new LongAdder();

	private static final long[] ORCHID_WAIT_NS = new long[LATENCY_SAMPLES];
	private static final AtomicInteger ORCHID_WAIT_IDX = new AtomicInteger();
	private static final AtomicLong ORCHID_WAIT_COUNT = new AtomicLong();
	private static final long[] OPLOG_FSYNC_NS = new long[LATENCY_SAMPLES];
	private static final AtomicInteger OPLOG_FSYNC_IDX = new AtomicInteger();
	private static final AtomicLong OPLOG_FSYNC_COUNT = new AtomicLong();

	private ReplicationMetrics() {
	}

	public static void recordOplogPushSent() {
		OPLOG_PUSH_SENT.increment();
	}

	public static void recordOplogPushRecv() {
		OPLOG_PUSH_RECV.increment();
	}

	public static void recordApplyAckSent() {
		APPLY_ACK_SENT.increment();
	}

	public static void recordApplyAckRecv() {
		APPLY_ACK_RECV.increment();
	}

	public static void recordApplyNackRecv() {
		APPLY_NACK_RECV.increment();
	}

	public static void recordMapGetHit() {
		MAP_GET_HIT.increment();
	}

	public static void recordMapGetMiss() {
		MAP_GET_MISS.increment();
	}

	public static void recordSealedMiss() {
		SEALED_MISSES.increment();
	}

	public static long sealedMisses() {
		return SEALED_MISSES.sum();
	}

	public static void recordAdaptiveModeChange() {
		ADAPTIVE_MODE_CHANGES.increment();
	}

	public static long adaptiveModeChanges() {
		return ADAPTIVE_MODE_CHANGES.sum();
	}

	public static void recordShipBackpressure() {
		SHIP_BACKPRESSURE.increment();
	}

	public static long shipBackpressure() {
		return SHIP_BACKPRESSURE.sum();
	}

	/** Apply delta to live overlay pin gauge (usually {@code +1} / {@code -1}). */
	public static void recordOverlayPinnedKeysDelta(long delta) {
		if (delta == 0L) {
			return;
		}
		OVERLAY_PINNED_KEYS.add(delta);
	}

	public static long overlayPinnedKeys() {
		return OVERLAY_PINNED_KEYS.sum();
	}

	public static void recordOrchidRpcSent() {
		ORCHID_RPC_SENT.increment();
	}

	public static void recordConnectFailure() {
		CONNECT_FAILURES.increment();
	}

	public static void recordOrchidWaitNs(long nanos) {
		sample(ORCHID_WAIT_NS, ORCHID_WAIT_IDX, ORCHID_WAIT_COUNT, nanos);
	}

	public static void recordOplogFsyncNs(long nanos) {
		sample(OPLOG_FSYNC_NS, OPLOG_FSYNC_IDX, OPLOG_FSYNC_COUNT, nanos);
	}

	public static long orchidWaitP50Ns() {
		return percentile(ORCHID_WAIT_NS, ORCHID_WAIT_COUNT.get(), 0.50);
	}

	public static long orchidWaitP99Ns() {
		return percentile(ORCHID_WAIT_NS, ORCHID_WAIT_COUNT.get(), 0.99);
	}

	public static long oplogFsyncP50Ns() {
		return percentile(OPLOG_FSYNC_NS, OPLOG_FSYNC_COUNT.get(), 0.50);
	}

	public static long oplogFsyncP99Ns() {
		return percentile(OPLOG_FSYNC_NS, OPLOG_FSYNC_COUNT.get(), 0.99);
	}

	/** Samples recorded for OpLog / tip group force (pressure signal for sealed dump backoff). */
	public static long oplogFsyncSampleCount() {
		return OPLOG_FSYNC_COUNT.get();
	}

	public static long oplogPushSent() {
		return OPLOG_PUSH_SENT.sum();
	}

	public static long oplogPushRecv() {
		return OPLOG_PUSH_RECV.sum();
	}

	public static long applyAckSent() {
		return APPLY_ACK_SENT.sum();
	}

	public static long applyAckRecv() {
		return APPLY_ACK_RECV.sum();
	}

	public static long applyNackRecv() {
		return APPLY_NACK_RECV.sum();
	}

	/** Approximate local read hit rate in [0,1]. */
	public static double mapHitRate() {
		final long hits = MAP_GET_HIT.sum();
		final long misses = MAP_GET_MISS.sum();
		final long total = hits + misses;
		if (total == 0) {
			final long acks = APPLY_ACK_RECV.sum();
			final long nacks = APPLY_NACK_RECV.sum();
			final long ackTotal = acks + nacks;
			return ackTotal == 0 ? 1.0 : (double) acks / (double) ackTotal;
		}
		return (double) hits / (double) total;
	}

	public static long orchidRpcSent() {
		return ORCHID_RPC_SENT.sum();
	}

	public static long connectFailures() {
		return CONNECT_FAILURES.sum();
	}

	public static long duplexMismatch() {
		return DUPLEX_MISMATCH.sum();
	}

	public static long duplexRepair() {
		return DUPLEX_REPAIR.sum();
	}

	public static long duplexUncorrectable() {
		return DUPLEX_UNCORRECTABLE.sum();
	}

	public static void recordDuplexMismatch() {
		DUPLEX_MISMATCH.increment();
	}

	public static void recordDuplexRepair() {
		DUPLEX_REPAIR.increment();
	}

	public static void recordDuplexUncorrectable() {
		DUPLEX_UNCORRECTABLE.increment();
	}

	private static void sample(long[] buf, AtomicInteger idx, AtomicLong count, long nanos) {
		if (nanos < 0) {
			return;
		}
		final int i = idx.getAndIncrement() & (LATENCY_SAMPLES - 1);
		buf[i] = nanos;
		count.incrementAndGet();
	}

	private static long percentile(long[] buf, long count, double p) {
		final int n = (int) Math.min(count, LATENCY_SAMPLES);
		if (n <= 0) {
			return 0L;
		}
		final long[] copy = Arrays.copyOf(buf, n);
		Arrays.sort(copy);
		final int i = Math.min(n - 1, (int) Math.ceil(p * n) - 1);
		return copy[Math.max(0, i)];
	}
}
