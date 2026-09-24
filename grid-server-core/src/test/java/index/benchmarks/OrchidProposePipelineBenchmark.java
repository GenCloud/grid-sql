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
import org.genfork.grid.replication.MutationRecorder;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH: pipelined ORCHID propose + MutationRecorder batch (group OpLog fsync).
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class OrchidProposePipelineBenchmark extends AbstractLatencyBenchmark {
	private static final int BATCH_SIZE = 16;

	private ReplicationCoordinator soloFsync;
	private MutationRecorder recorder;
	private Path dataDir;
	private final AtomicLong seqHint = new AtomicLong(1);

	@Setup
	public void setup() throws Exception {
		dataDir = Files.createTempDirectory("orchid-pipeline-bench");
		soloFsync = startSolo(dataDir.resolve("on"), true);
		final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		recorder = soloFsync.registerDomain("pipeline_bench", ReplTestSupport.singleShard(proc), true);
	}

	private static ReplicationCoordinator startSolo(Path dir, boolean fsync) throws Exception {
		int port;
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			port = s.getLocalPort();
		}
		final GridConfigurationProperties props =
				ReplTestSupport.props("pipe-fsync", "pipe-fsync", "dc-a", port, dir, List.of());
		props.getReplication().getOpLog().setFsync(fsync);
		props.getReplication().getOrchid().setMaxProposeInFlight(32);
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
		if (soloFsync != null) {
			soloFsync.stop();
		}
	}

	@Benchmark
	public void pipelinedProposeBatch(Blackhole bh) {
		final List<ReplicationOp> ops = new ArrayList<>(BATCH_SIZE);
		for (int i = 0; i < BATCH_SIZE; i++) {
			final long n = seqHint.getAndIncrement();
			ops.add(OpLogCodec.withChecksum(new ReplicationOp(
					"pipeline_bench", 0, n, ReplicationOpType.UPSERT,
					new byte[]{(byte) n, (byte) (n >> 8), (byte) (n >> 16)},
					new byte[]{1, 2, 3},
					1L, 0L)));
		}
		final long[] seqs = soloFsync.getOrchidNode().appendAndWaitCommitBatch(ops).join();
		bh.consume(seqs.length);
	}

	@Benchmark
	public void mutationRecorderBatch(Blackhole bh) {
		final List<GridEntriesProcessor.Entry> entries = new ArrayList<>(BATCH_SIZE);
		for (int i = 0; i < BATCH_SIZE; i++) {
			final long n = seqHint.getAndIncrement();
			entries.add(new GridEntriesProcessor.AddEntry(
					null,
					new byte[]{(byte) n, (byte) (n >> 8), (byte) (n >> 16)},
					new byte[]{1, 2, 3, 4}));
		}
		recorder.recordCommittedBatchBlocking(0, entries);
		bh.consume(entries.size());
	}

	@Benchmark
	public void txUnitBeginDataCommit(Blackhole bh) {
		final long txId = seqHint.getAndIncrement();
		final List<GridEntriesProcessor.Entry> entries = new ArrayList<>(1);
		final long n = seqHint.getAndIncrement();
		entries.add(new GridEntriesProcessor.AddEntry(
				null,
				new byte[]{(byte) n, (byte) (n >> 8), (byte) (n >> 16)},
				new byte[]{1, 2, 3, 4}));
		recorder.recordTxUnitBlocking(0, txId, null, entries);
		bh.consume(txId);
	}

	@Benchmark
	public void txMarkersSeparateThenData(Blackhole bh) {
		final long txId = seqHint.getAndIncrement();
		final byte[] markerKey = Long.toHexString(txId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		soloFsync.recordTxMarker(
				ReplicationOpType.TX_BEGIN, txId, "pipeline_bench", 0);
		final List<GridEntriesProcessor.Entry> entries = new ArrayList<>(1);
		final long n = seqHint.getAndIncrement();
		entries.add(new GridEntriesProcessor.AddEntry(
				null,
				new byte[]{(byte) n, (byte) (n >> 8), (byte) (n >> 16)},
				new byte[]{1, 2, 3, 4}));
		recorder.recordCommittedBatchBlocking(0, entries);
		soloFsync.recordTxMarker(
				ReplicationOpType.TX_COMMIT, txId, "pipeline_bench", 0);
		bh.consume(markerKey.length);
	}
}
