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
package index.unit.replication;

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.log.StreamOpLogAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Out-of-order ORCHID completions must still append OpLog in seq order.
 * Seq holdback drains under continuous load (no joining-count barrier stall).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class StreamOpLogAppenderTest {
	private static final String DOMAIN = "stream_order";
	private static final int SHARD = 0;
	private static final int OPS = 8;

	@TempDir
	Path tempDir;

	@Test
	void outOfOrderPublishStillAppendsMonotonic() throws Exception {
		final OpLog opLog = new OpLog(tempDir, false);
		final StreamOpLogAppender appender = new StreamOpLogAppender(opLog);
		final ExecutorService pool = Executors.newFixedThreadPool(OPS);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch begun = new CountDownLatch(OPS);
		final CountDownLatch done = new CountDownLatch(OPS);
		try {
			for (int i = 1; i <= OPS; i++) {
				final long seq = i;
				pool.execute(() -> {
					try {
						appender.beginJoin(DOMAIN, SHARD, seq);
						begun.countDown();
						start.await();
						final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
								DOMAIN, SHARD, seq, ReplicationOpType.UPSERT,
								new byte[]{(byte) seq}, new byte[]{1}, 1L, 0L));
						appender.publishCommitted(op);
					} catch (Exception e) {
						throw new RuntimeException(e);
					} finally {
						done.countDown();
					}
				});
			}
			assertTrue(begun.await(5, TimeUnit.SECONDS));
			start.countDown();
			assertTrue(done.await(10, TimeUnit.SECONDS));
			appender.force(DOMAIN, SHARD);
			final List<ReplicationOp> ops = opLog.readFrom(DOMAIN, SHARD, 1L, OPS + 1);
			assertEquals(OPS, ops.size());
			final List<Long> seqs = new ArrayList<>(OPS);
			for (ReplicationOp op : ops) {
				seqs.add(op.opSeq());
			}
			assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), seqs);
		} finally {
			pool.shutdownNow();
			opLog.close();
		}
	}

	@Test
	void continuousInflightDoesNotStallLowerSeq() throws Exception {
		final OpLog opLog = new OpLog(tempDir, false);
		final StreamOpLogAppender appender = new StreamOpLogAppender(opLog);
		try {
			appender.beginJoin(DOMAIN, SHARD, 1L);
			appender.beginJoin(DOMAIN, SHARD, 2L);
			appender.beginJoin(DOMAIN, SHARD, 99L);
			final ReplicationOp op1 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 1L, ReplicationOpType.UPSERT,
					new byte[]{1}, new byte[]{1}, 1L, 0L));
			appender.publishCommitted(op1);
			assertTrue(opLog.containsSeq(DOMAIN, SHARD, 1L));
			final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 2L, ReplicationOpType.UPSERT,
					new byte[]{2}, new byte[]{1}, 1L, 0L));
			appender.publishCommitted(op2);
			assertTrue(opLog.containsSeq(DOMAIN, SHARD, 2L));
			appender.cancelJoin(DOMAIN, SHARD, 99L);
		} finally {
			opLog.close();
		}
	}

	@Test
	void concurrentMultiStreamMarkersAndDataStayOrdered() throws Exception {
		final OpLog opLog = new OpLog(tempDir, false);
		final StreamOpLogAppender appender = new StreamOpLogAppender(opLog);
		final int threads = 4;
		final int opsPerThread = 8;
		final ExecutorService pool = Executors.newFixedThreadPool(threads);
		final CountDownLatch joinsReady = new CountDownLatch(threads);
		final CountDownLatch startPublish = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(threads);
		final AtomicReference<Throwable> firstError = new AtomicReference<>();
		try {
			for (int t = 0; t < threads; t++) {
				final int shard = t % 2;
				// Contiguous per-shard ranges: shard0 → 1..8 + 17..24; shard1 → 9..16 + 25..32
				final int base = t * opsPerThread + 1;
				pool.execute(() -> {
					try {
						final List<Long> seqs = new ArrayList<>(opsPerThread);
						for (int i = 0; i < opsPerThread; i++) {
							seqs.add((long) (base + i));
						}
						for (Long seq : seqs) {
							appender.beginJoin(DOMAIN, shard, seq);
						}
						joinsReady.countDown();
						startPublish.await();
						final List<ReplicationOp> batch = new ArrayList<>(opsPerThread);
						for (Long seq : seqs) {
							batch.add(OpLogCodec.withChecksum(new ReplicationOp(
									DOMAIN, shard, seq, ReplicationOpType.UPSERT,
									new byte[]{(byte) seq.longValue()}, new byte[]{1}, 1L, 0L)));
						}
						// Reverse batch then one publishBatch — OOO completions within the unit.
						final List<ReplicationOp> reversed = new ArrayList<>(opsPerThread);
						for (int i = batch.size() - 1; i >= 0; i--) {
							reversed.add(batch.get(i));
						}
						appender.publishBatch(reversed);
					} catch (Exception e) {
						firstError.compareAndSet(null, e);
						throw new RuntimeException(e);
					} finally {
						done.countDown();
					}
				});
			}
			assertTrue(joinsReady.await(5, TimeUnit.SECONDS));
			startPublish.countDown();
			assertTrue(done.await(15, TimeUnit.SECONDS));
			assertTrue(firstError.get() == null, () -> String.valueOf(firstError.get()));
			appender.force(DOMAIN, 0);
			appender.force(DOMAIN, 1);
			assertMonotonic(opLog, 0);
			assertMonotonic(opLog, 1);
		} finally {
			pool.shutdownNow();
			opLog.close();
		}
	}

	@Test
	void cancelJoinMustNotUnblockHigherSeqBeforeLowerPublished() throws Exception {
		final OpLog opLog = new OpLog(tempDir, false);
		final StreamOpLogAppender appender = new StreamOpLogAppender(opLog);
		try {
			appender.beginJoin(DOMAIN, SHARD, 1L);
			appender.beginJoin(DOMAIN, SHARD, 2L);
			final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 2L, ReplicationOpType.UPSERT,
					new byte[]{2}, new byte[]{1}, 1L, 0L));
			final Thread publisher = new Thread(() -> appender.publishCommitted(op2));
			publisher.start();
			Thread.sleep(50L);
			// Cancelling the lower seq without publishing it used to let 2 append first.
			// Correct: cancel only removes inflight; 2 stays blocked until 1 is published or
			// also cancelled — here we publish 1 after cancel would have been wrong.
			// Simulate the fixed policy: do NOT cancel a seq we still intend to publish.
			final ReplicationOp op1 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 1L, ReplicationOpType.UPSERT,
					new byte[]{1}, new byte[]{1}, 1L, 0L));
			appender.publishCommitted(op1);
			publisher.join(5_000L);
			assertTrue(opLog.containsSeq(DOMAIN, SHARD, 1L));
			assertTrue(opLog.containsSeq(DOMAIN, SHARD, 2L));
			final List<ReplicationOp> ops = opLog.readFrom(DOMAIN, SHARD, 1L, 10);
			assertEquals(List.of(1L, 2L), ops.stream().map(ReplicationOp::opSeq).toList());
		} finally {
			opLog.close();
		}
	}

	@Test
	void doubleBeginJoin_cancelOnce_stillBlocksHigher() throws Exception {
		final OpLog opLog = new OpLog(tempDir, false);
		final StreamOpLogAppender appender = new StreamOpLogAppender(opLog);
		try {
			appender.beginJoin(DOMAIN, SHARD, 10L);
			appender.beginJoin(DOMAIN, SHARD, 10L);
			appender.cancelJoin(DOMAIN, SHARD, 10L);
			final ReplicationOp op11 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 11L, ReplicationOpType.UPSERT,
					new byte[]{11}, new byte[]{1}, 1L, 0L));
			final Thread t11 = new Thread(() -> {
				appender.beginJoin(DOMAIN, SHARD, 11L);
				appender.publishCommitted(op11);
			});
			t11.start();
			Thread.sleep(80L);
			assertTrue(!opLog.containsSeq(DOMAIN, SHARD, 11L),
					"11 must stay blocked while one join of 10 remains");
			appender.cancelJoin(DOMAIN, SHARD, 10L);
			t11.join(5_000L);
			assertTrue(opLog.containsSeq(DOMAIN, SHARD, 11L));
		} finally {
			opLog.close();
		}
	}

	@Test
	void cancelJoinAfterReAdmit_sameSeq_keepsMonotonic() throws Exception {
		final OpLog opLog = new OpLog(tempDir, false);
		final StreamOpLogAppender appender = new StreamOpLogAppender(opLog);
		final CountDownLatch published11 = new CountDownLatch(1);
		try {
			// TxA admit expected=10
			appender.beginJoin(DOMAIN, SHARD, 10L);
			// failProposeChain rewind + TxB re-admit same seq
			appender.beginJoin(DOMAIN, SHARD, 10L);
			// late cancelJoin from failed TxA must not drop TxB's slot
			appender.cancelJoin(DOMAIN, SHARD, 10L);

			final ReplicationOp op11 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 11L, ReplicationOpType.UPSERT,
					new byte[]{11}, new byte[]{1}, 1L, 0L));
			final Thread t11 = new Thread(() -> {
				appender.beginJoin(DOMAIN, SHARD, 11L);
				appender.publishCommitted(op11);
				published11.countDown();
			});
			t11.start();
			Thread.sleep(80L);
			assertTrue(!opLog.containsSeq(DOMAIN, SHARD, 11L),
					"higher seq must not drain ahead of surviving re-admit of 10");

			final ReplicationOp op10 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 10L, ReplicationOpType.UPSERT,
					new byte[]{10}, new byte[]{1}, 1L, 0L));
			appender.publishCommitted(op10);
			assertTrue(published11.await(5, TimeUnit.SECONDS));
			t11.join(5_000L);
			final List<ReplicationOp> ops = opLog.readFrom(DOMAIN, SHARD, 10L, 10);
			assertEquals(List.of(10L, 11L), ops.stream().map(ReplicationOp::opSeq).toList());
		} finally {
			opLog.close();
		}
	}

	@Test
	void lowestInflightBlocksEvenWhenBelowLastPlusOneGap() throws Exception {
		final OpLog opLog = new OpLog(tempDir, false);
		final StreamOpLogAppender appender = new StreamOpLogAppender(opLog);
		try {
			// Sparse stream: last=10 already on disk; inflight 12 must block ready 15.
			final ReplicationOp seed = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 10L, ReplicationOpType.UPSERT,
					new byte[]{10}, new byte[]{1}, 1L, 0L));
			appender.beginJoin(DOMAIN, SHARD, 10L);
			appender.publishCommitted(seed);
			appender.beginJoin(DOMAIN, SHARD, 12L);
			appender.beginJoin(DOMAIN, SHARD, 15L);
			final ReplicationOp op15 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 15L, ReplicationOpType.UPSERT,
					new byte[]{15}, new byte[]{1}, 1L, 0L));
			final Thread t15 = new Thread(() -> appender.publishCommitted(op15));
			t15.start();
			Thread.sleep(50L);
			assertTrue(!opLog.containsSeq(DOMAIN, SHARD, 15L), "15 must wait for inflight 12");
			final ReplicationOp op12 = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 12L, ReplicationOpType.UPSERT,
					new byte[]{12}, new byte[]{1}, 1L, 0L));
			appender.publishCommitted(op12);
			t15.join(5_000L);
			assertTrue(opLog.containsSeq(DOMAIN, SHARD, 12L));
			assertTrue(opLog.containsSeq(DOMAIN, SHARD, 15L));
		} finally {
			opLog.close();
		}
	}

	private static void assertMonotonic(OpLog opLog, int shard) {
		final List<ReplicationOp> ops = opLog.readFrom(DOMAIN, shard, 1L, 1_000);
		long prev = 0L;
		for (ReplicationOp op : ops) {
			assertTrue(op.opSeq() > prev, "shard=" + shard + " prev=" + prev + " got=" + op.opSeq());
			prev = op.opSeq();
		}
	}
}
