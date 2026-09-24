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

import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH: OpLog append fsync on/off.
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
public class OpLogAppendBenchmark extends AbstractLatencyBenchmark {
	private OpLog opLogOff;
	private OpLog opLogOn;
	private final AtomicLong seq = new AtomicLong(1);

	@Setup
	public void setup() throws Exception {
		final Path root = Files.createTempDirectory("oplog-bench");
		opLogOff = new OpLog(root.resolve("off"), false);
		opLogOn = new OpLog(root.resolve("on"), true);
	}

	@TearDown
	public void tearDown() throws Exception {
		if (opLogOff != null) {
			opLogOff.close();
		}
		if (opLogOn != null) {
			opLogOn.close();
		}
		System.out.printf("oplogFsync p50=%d p99=%d%n",
				ReplicationMetrics.oplogFsyncP50Ns(), ReplicationMetrics.oplogFsyncP99Ns());
	}

	@Benchmark
	public void appendFsyncOff(Blackhole bh) {
		bh.consume(append(opLogOff));
	}

	@Benchmark
	public void appendFsyncOn(Blackhole bh) {
		bh.consume(append(opLogOn));
	}

	private long append(OpLog log) {
		final long n = seq.getAndIncrement();
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"oplog", 0, n, ReplicationOpType.UPSERT,
				new byte[]{(byte) n}, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 1L, 0L
		));
		log.append(op);
		return n;
	}
}
