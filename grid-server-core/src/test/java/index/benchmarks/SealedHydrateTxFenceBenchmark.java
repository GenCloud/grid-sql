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

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.util.SealedHydrateTxFenceUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH: per-stream open-TX fence cost around sealed/LAZY OpLog hydrate.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SealedHydrateTxFenceBenchmark extends AbstractLatencyBenchmark {
	private static final String DOMAIN = "fence_jmh";
	private static final int SHARD = 0;
	private static final long NODE_SCHEMA_EPOCH = 1L;
	private static final long CHECKSUM_PLACEHOLDER = 0L;
	private static final byte[] TX_KEY = new byte[]{1};
	private static final byte[] ROW_KEY = new byte[]{10};
	private static final byte[] ROW_VALUE = new byte[]{99};

	private Path dataDir;
	private OpLog opLog;
	private ReplicaApplier applier;
	private final AtomicLong nextSeq = new AtomicLong(1L);

	@Setup(Level.Trial)
	public void setup() throws Exception {
		dataDir = Files.createTempDirectory("sealed-hydrate-tx-fence-jmh");
		final ReplicationNodeState state =
				new ReplicationNodeState("fj-1", "fj", "dc-a", NODE_SCHEMA_EPOCH);
		opLog = new OpLog(dataDir.resolve("oplog"), false);
		final GridEntriesProcessor processor =
				new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
		applier = new ReplicaApplier(state, opLog, shard -> processor, null, true);
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		if (opLog != null) {
			opLog.close();
		}
	}

	@Setup(Level.Invocation)
	public void openTx() {
		final long beginSeq = nextSeq.getAndIncrement();
		final long upsertSeq = nextSeq.getAndIncrement();
		applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, beginSeq, ReplicationOpType.TX_BEGIN,
				TX_KEY, null, NODE_SCHEMA_EPOCH, CHECKSUM_PLACEHOLDER
		)), false);
		applier.apply(OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, upsertSeq, ReplicationOpType.UPSERT,
				ROW_KEY, ROW_VALUE, NODE_SCHEMA_EPOCH, CHECKSUM_PLACEHOLDER
		)), false);
	}

	@Benchmark
	public void beforeFenceDiscardsOpenTx(Blackhole bh) {
		SealedHydrateTxFenceUtil.beforeSealedOrLazyOpLogHydrate(applier, DOMAIN, SHARD);
		bh.consume(applier.hasOpenTx(DOMAIN, SHARD));
	}

	@Benchmark
	public void afterFenceDiscardsOpenTx(Blackhole bh) {
		SealedHydrateTxFenceUtil.afterOpLogHydrate(applier, DOMAIN, SHARD);
		bh.consume(applier.hasOpenTx(DOMAIN, SHARD));
	}
}
