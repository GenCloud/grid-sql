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
package index.benchmarks;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.sql.tx.ForUpdatePrepareWireUtil.TableKey;
import org.genfork.grid.sql.tx.LockWaitTimeoutException;
import org.genfork.grid.sql.tx.NettyDistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import org.genfork.grid.threading.ThreadService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH: Dist FOR UPDATE peer-lock failure modes (NACK, unknown peer).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class ForUpdateDistFailureBenchmark extends AbstractLatencyBenchmark {
	private static final String CLUSTER = "fu-fail-jmh";
	private static final String DC = "dc0";
	private static final String NODE_A = "jmh-fail-a";
	private static final String NODE_B = "jmh-fail-b";
	private static final String TABLE = "fu_fail";
	private static final byte[] KEY = new byte[]{8, 8};
	private static final byte[] MISSING_KEY = new byte[]{1, 1};
	private static final long CONNECT_TIMEOUT_MS = 3_000L;
	private static final int MAX_FRAME = 1 << 20;
	private static final long AWAIT_MS = 5_000L;
	private static final long POLL_MS = 25L;
	private static final long TX_ID = 1L;

	private NettyReplicationTransport transportA;
	private NettyReplicationTransport transportB;
	private NettyDistForUpdatePeerLockAgent agent;
	private long nextTxId = TX_ID;

	@Setup(Level.Trial)
	public void setupBenchmark() throws Exception {
		ThreadService.ensureRunning();
		final int portA = freePort();
		final int portB = freePort();
		final SqlRecordLockManager locksA = new SqlRecordLockManager();
		final SqlRecordLockManager locksB = new SqlRecordLockManager();
		locksA.setLockWaitTimeoutMs(200L);
		locksB.setLockWaitTimeoutMs(200L);

		transportA = new NettyReplicationTransport(
				NODE_A, CLUSTER, DC, 1L, "127.0.0.1", portA, CONNECT_TIMEOUT_MS, MAX_FRAME,
				List.of(new ReplicationPeer(NODE_B, "127.0.0.1", portB, DC)));
		transportB = new NettyReplicationTransport(
				NODE_B, CLUSTER, DC, 1L, "127.0.0.1", portB, CONNECT_TIMEOUT_MS, MAX_FRAME,
				List.of(new ReplicationPeer(NODE_A, "127.0.0.1", portA, DC)));
		bindMinimal(transportA);
		bindMinimal(transportB);
		transportA.setForUpdateLockManager(locksA);
		transportB.setForUpdateLockManager(locksB);
		transportA.start();
		transportB.start();
		awaitReady(transportA, NODE_B);
		agent = new NettyDistForUpdatePeerLockAgent(transportA, NODE_B);
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() {
		if (transportA != null) {
			transportA.close();
		}
		if (transportB != null) {
			transportB.close();
		}
	}

	@Benchmark
	public void prepareNackPartialKeySet(Blackhole bh) {
		final long txId = nextTxId++;
		agent.lock(txId, TABLE, KEY, false);
		final boolean prepared = transportA.sendForUpdatePrepareReq(
				NODE_B,
				txId,
				List.of(new TableKey(TABLE, KEY), new TableKey(TABLE, MISSING_KEY)));
		bh.consume(prepared);
		agent.unlock(txId, TABLE, KEY);
	}

	@Benchmark
	public void unknownPeerLockFailsClosed(Blackhole bh) {
		final NettyDistForUpdatePeerLockAgent dead =
				new NettyDistForUpdatePeerLockAgent(transportA, "no-such-peer");
		final long txId = nextTxId++;
		boolean timedOut = false;
		try {
			dead.lock(txId, TABLE, KEY, false);
		} catch (LockWaitTimeoutException ex) {
			timedOut = true;
		}
		bh.consume(timedOut);
	}

	private static void bindMinimal(NettyReplicationTransport transport) {
		transport.bindHandlers(
				() -> null,
				domain -> null,
				null,
				null,
				null,
				null,
				null,
				peer -> {
				},
				peerId -> {
				},
				() -> 0L,
				() -> (byte) 0);
	}

	private static void awaitReady(NettyReplicationTransport transport, String peerId)
			throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MS);
		while (System.nanoTime() < deadline) {
			try {
				if (transport.sendForUpdateLockReq(peerId, 0L, true, TABLE, new byte[]{0})) {
					transport.sendForUpdateLockRelease(peerId, 0L, TABLE, new byte[]{0});
					return;
				}
			} catch (RuntimeException ignored) {
				// connect race
			}
			Thread.sleep(POLL_MS);
		}
		throw new IllegalStateException("peer channel not ready");
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}
}