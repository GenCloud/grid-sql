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
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.MutationRecorder;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH: solo / fsync-on / MutationRecorder ORCHID+OpLog path.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class OrchidCommitLatencyBenchmark extends AbstractLatencyBenchmark {
	private ReplicationCoordinator solo;
	private ReplicationCoordinator soloFsync;
	private MutationRecorder recorder;
	private Path dataDir;
	private final AtomicLong seqHint = new AtomicLong(1);

	@Setup
	public void setup() throws Exception {
		dataDir = Files.createTempDirectory("orchid-bench");
		solo = startSolo(dataDir.resolve("off"), false);
		soloFsync = startSolo(dataDir.resolve("on"), true);
		final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		recorder = solo.registerDomain("commit_latency", ReplTestSupport.singleShard(proc), true);
	}

	private static ReplicationCoordinator startSolo(Path dir, boolean fsync) throws Exception {
		int port;
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			port = s.getLocalPort();
		}
		// Unique node/cluster ids so concurrent solo instances never peer-discover each other.
		final String tag = fsync ? "fsync" : "nosync";
		final var props = ReplTestSupport.props("bench-" + tag, "bench-" + tag, "dc-a", port, dir, List.of());
		props.getReplication().getOpLog().setFsync(fsync);
		final ReplicationCoordinator c = new ReplicationCoordinator(props);
		c.start();
		final long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline && !c.getOrchidNode().isSynced()) {
			Thread.sleep(10);
		}
		return c;
	}

	@TearDown
	public void tearDown() {
		if (solo != null) {
			solo.stop();
		}
		if (soloFsync != null) {
			soloFsync.stop();
		}
		System.out.printf("orchidWait p50=%d p99=%d fsync p50=%d p99=%d%n",
				ReplicationMetrics.orchidWaitP50Ns(), ReplicationMetrics.orchidWaitP99Ns(),
				ReplicationMetrics.oplogFsyncP50Ns(), ReplicationMetrics.oplogFsyncP99Ns());
	}

	@Benchmark
	public void soloOrchidCommit(Blackhole bh) {
		bh.consume(commit(solo));
	}

	@Benchmark
	public void soloOrchidCommitFsyncOn(Blackhole bh) {
		bh.consume(commit(soloFsync));
	}

	@Benchmark
	public void mutationRecorderPath(Blackhole bh) {
		final long n = seqHint.getAndIncrement();
		final byte[] key = new byte[]{(byte) n, (byte) (n >> 8)};
		final byte[] value = new byte[]{1, 2, 3, 4};
		final long t0 = System.nanoTime();
		final long seq = recorder.recordCommittedBlocking(0, new GridEntriesProcessor.AddEntry(null, key, value));
		ReplicationMetrics.recordOrchidWaitNs(System.nanoTime() - t0);
		bh.consume(seq);
	}

	private long commit(ReplicationCoordinator c) {
		final long n = seqHint.getAndIncrement();
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"commit_latency", 0, n, ReplicationOpType.UPSERT,
				new byte[]{(byte) n, (byte) (n >> 8)},
				new byte[]{1, 2, 3},
				1L, 0L
		));
		final long t0 = System.nanoTime();
		final long seq = c.getOrchidNode().appendAndWaitCommit(op).join();
		ReplicationMetrics.recordOrchidWaitNs(System.nanoTime() - t0);
		return seq;
	}
}
