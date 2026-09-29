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

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.orchid.OrchidNode;
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

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH: caught-up admit vs tip-behind reject on the G0 fence hot path.
 * <p>
 * Measures fail-closed tip fence cost without weakening quorum / fsync gates.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class OrchidTipFenceAdmitBenchmark extends AbstractLatencyBenchmark {

	private static final String DOMAIN = "tip_fence_admit";
	private static final String FAKE_PEER = "tf-peer";
	private static final long TIP_AHEAD_DELTA = 3L;
	private static final long SYNC_TIMEOUT_MS = 8_000L;
	private static final int MAX_PROPOSE_IN_FLIGHT = 64;

	private ReplicationCoordinator coord;
	private OrchidNode orchid;
	private Path dataDir;
	private final AtomicLong seqHint = new AtomicLong(1L);
	private long caughtUpTip;
	private long behindPeerTip;

	@Setup(Level.Trial)
	public void setup() throws Exception {
		dataDir = Files.createTempDirectory("orchid-tip-fence-jmh");
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		final GridConfigurationProperties props =
				ReplTestSupport.props("tf-1", "tf", "dc-a", port, dataDir, List.of());
		props.getReplication().getOrchid().setOrderThreshold(0.0);
		props.getReplication().getOrchid().setMaxProposeInFlight(MAX_PROPOSE_IN_FLIGHT);
		coord = new ReplicationCoordinator(props);
		final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		coord.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc), true);
		coord.start();
		final long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline && !coord.getOrchidNode().isSynced()) {
			Thread.sleep(10L);
		}
		if (!coord.getOrchidNode().isSynced()) {
			throw new IllegalStateException("orchid not synced for tip-fence JMH");
		}
		orchid = coord.getOrchidNode();
		final ReplicationOp seed = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, 1L, ReplicationOpType.UPSERT,
				new byte[]{1}, new byte[]{2}, 1L, 0L));
		caughtUpTip = orchid.appendAndWaitCommit(seed).get(8L, TimeUnit.SECONDS);
		behindPeerTip = caughtUpTip + TIP_AHEAD_DELTA;
		seqHint.set(caughtUpTip + 1L);
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		if (coord != null) {
			try {
				coord.stop();
			} catch (Exception ignored) {
			}
		}
	}

	@Benchmark
	public void caughtUpAdmit(Blackhole bh) {
		orchid.testingNotePeerCommittedSeq(FAKE_PEER, caughtUpTip);
		final long n = seqHint.getAndIncrement();
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, n, ReplicationOpType.UPSERT,
				new byte[]{(byte) n, (byte) (n >> 8)},
				new byte[]{3, 4},
				1L, 0L));
		final long seq = orchid.appendAndWaitCommit(op).join();
		caughtUpTip = seq;
		behindPeerTip = caughtUpTip + TIP_AHEAD_DELTA;
		bh.consume(seq);
	}

	@Benchmark
	public void tipBehindReject(Blackhole bh) {
		orchid.testingNotePeerCommittedSeq(FAKE_PEER, behindPeerTip);
		final long n = seqHint.get();
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, 0, n, ReplicationOpType.UPSERT,
				new byte[]{(byte) n, (byte) (n >> 8)},
				new byte[]{5, 6},
				1L, 0L));
		boolean denied = false;
		try {
			orchid.appendAndWaitCommit(op).join();
		} catch (CompletionException ex) {
			denied = rootCauseIsTipBehind(ex);
		}
		bh.consume(denied);
		bh.consume(coord.isWriterEligible());
	}

	private static boolean rootCauseIsTipBehind(Throwable thrown) {
		Throwable cur = thrown;
		while (cur != null) {
			if (cur instanceof OrchidNotSyncedException) {
				final String msg = cur.getMessage();
				return msg != null && msg.contains("tip behind");
			}
			cur = cur.getCause();
		}
		return false;
	}
}
