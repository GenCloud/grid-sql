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
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.util.OrchidTipCatchUpUtil;
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
import java.util.concurrent.TimeUnit;

/**
 * JMH: journalBound scan and tip catch-up (journal / sealed-baseline no-op paths).
 * <p>
 * Does not run on the admit hot path — fence remains O(1) in {@code appendAndAdmit}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class OrchidTipCatchUpBenchmark extends AbstractLatencyBenchmark {

	private static final String DOMAIN = "tip_catch_up_jmh";
	private static final String NODE_ID = "tc-1";
	private static final String CLUSTER_ID = "tc";
	private static final String LOCAL_DC = "dc-a";
	private static final int SHARD = 0;
	private static final long SYNC_TIMEOUT_MS = 8_000L;
	private static final long SYNC_POLL_MS = 10L;
	private static final long PEER_TIP_AHEAD = 5L;
	private static final long SEED_HINT_SEQ = 1L;
	private static final long COMMIT_TIMEOUT_SEC = 8L;
	private static final double ORDER_THRESHOLD_IT = 0.0;
	private static final int PACKS_NONE = 0;
	private static final byte[] SEED_KEY = new byte[]{1};
	private static final byte[] SEED_VALUE = new byte[]{2};
	private static final long TX_ID = 1L;
	private static final long PREV_OP_SEQ = 0L;

	private ReplicationCoordinator coord;
	private OrchidNode orchid;
	private OpLog opLog;
	private Path dataDir;
	private long journalTip;
	private long peerTipAhead;

	@Setup(Level.Trial)
	public void setup() throws Exception {
		dataDir = Files.createTempDirectory("orchid-tip-catchup-jmh");
		final int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		final GridConfigurationProperties props =
				ReplTestSupport.props(NODE_ID, CLUSTER_ID, LOCAL_DC, port, dataDir, List.of());
		props.getReplication().getOrchid().setOrderThreshold(ORDER_THRESHOLD_IT);
		coord = new ReplicationCoordinator(props);
		final GridEntriesProcessor proc = new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
		coord.registerDomain(DOMAIN, ReplTestSupport.singleShard(proc), true);
		coord.start();
		final long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline && !coord.getOrchidNode().isSynced()) {
			Thread.sleep(SYNC_POLL_MS);
		}
		if (!coord.getOrchidNode().isSynced()) {
			throw new IllegalStateException("orchid not synced for tip-catch-up JMH");
		}
		orchid = coord.getOrchidNode();
		opLog = coord.getOpLog();
		final ReplicationOp seed = OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, SEED_HINT_SEQ, ReplicationOpType.UPSERT,
				SEED_KEY, SEED_VALUE, TX_ID, PREV_OP_SEQ));
		journalTip = orchid.appendAndWaitCommit(seed).get(COMMIT_TIMEOUT_SEC, TimeUnit.SECONDS);
		peerTipAhead = journalTip + PEER_TIP_AHEAD;
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
	public void journalBoundScan(Blackhole bh) {
		bh.consume(OrchidTipCatchUpUtil.journalBound(opLog));
	}

	@Benchmark
	public void catchUpFromJournalNoOp(Blackhole bh) {
		bh.consume(OrchidTipCatchUpUtil.catchUpFromJournal(orchid, opLog));
	}

	@Benchmark
	public void catchUpPeerTipNoPacksNoOp(Blackhole bh) {
		bh.consume(OrchidTipCatchUpUtil.catchUpToPeerTipAfterSealedBaselineRound(
				orchid, opLog, peerTipAhead, PACKS_NONE));
	}

	@Benchmark
	public void catchUpPeerTipAlreadyCoveredNoOp(Blackhole bh) {
		bh.consume(OrchidTipCatchUpUtil.catchUpToPeerTipAfterSealedBaselineRound(
				orchid, opLog, journalTip, OrchidTipCatchUpUtil.MIN_SEALED_PACKS_FOR_PEER_TIP));
	}
}
