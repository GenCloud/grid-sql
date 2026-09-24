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

import index.sql.SqlBenchHelper;
import index.unit.replication.ReplTestSupport;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.sql.SqlEngine;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fair durable solo commit (fsync on) via SqlEngine INSERT → TableStore / MutationRecorder(table).
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
public class OrchidDurableCompareBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "durable";

	private ReplicationCoordinator solo;
	private SqlEngine engine;
	private final AtomicLong seq = new AtomicLong(1);

	@Setup
	public void setup() throws Exception {
		final Path dir = Files.createTempDirectory("orchid-durable");
		int port;
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			port = s.getLocalPort();
		}
		final var props = ReplTestSupport.props("durable-1", "durable", "dc-a", port, dir, List.of());
		props.getReplication().getOpLog().setFsync(true);
		solo = new ReplicationCoordinator(props);
		solo.start();
		final long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline && !solo.getOrchidNode().isSynced()) {
			Thread.sleep(10);
		}
		engine = SqlBenchHelper.createEngine(1, solo);
		SqlBenchHelper.ensureKvTable(engine, TABLE);
	}

	@TearDown
	public void tearDown() {
		if (engine != null && engine.catalog().exists(TABLE)) {
			try {
				engine.catalog().dropTable(TABLE);
			} catch (Exception ignored) {
				// shutting down
			}
		}
		if (solo != null) {
			solo.stop();
		}
		System.out.printf("durable orchidWait p50=%d p99=%d fsync p50=%d p99=%d%n",
				ReplicationMetrics.orchidWaitP50Ns(), ReplicationMetrics.orchidWaitP99Ns(),
				ReplicationMetrics.oplogFsyncP50Ns(), ReplicationMetrics.oplogFsyncP99Ns());
	}

	@Benchmark
	public void durableMutationRecorder(Blackhole bh) {
		final long n = seq.getAndIncrement();
		bh.consume(engine.execute(
				"INSERT INTO " + TABLE + " (id, v) VALUES (" + n + ", 'xxxxxxxx')").rowsAffected());
	}
}
