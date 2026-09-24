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
import org.genfork.grid.context.config.GridConfigurationProperties;
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
 * JMH: 2-node localhost ORCHID propose/commit latency via SqlEngine INSERT (fsync off).
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
public class TwoNodeOrchidCommitBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "two";

	private ReplicationCoordinator a;
	private ReplicationCoordinator b;
	private SqlEngine engineA;
	private SqlEngine engineB;
	private final AtomicLong seqHint = new AtomicLong(1);

	@Setup
	public void setup() throws Exception {
		final Path root = Files.createTempDirectory("orchid-2n");
		int portA;
		int portB;
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			portA = s.getLocalPort();
		}
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			portB = s.getLocalPort();
		}
		final GridConfigurationProperties propsA = ReplTestSupport.safetyProps(
				"jmh-a", "jmh2", "dc-a", portA, root.resolve("a"),
				List.of(ReplTestSupport.peer("jmh-b", "dc-a", portB))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.safetyProps(
				"jmh-b", "jmh2", "dc-a", portB, root.resolve("b"),
				List.of(ReplTestSupport.peer("jmh-a", "dc-a", portA))
		);
		a = new ReplicationCoordinator(propsA);
		b = new ReplicationCoordinator(propsB);
		a.start();
		b.start();
		final long deadline = System.currentTimeMillis() + 12_000;
		while (System.currentTimeMillis() < deadline
				&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced()
				|| !a.getOrchidNode().isPhaseRankedProposer())) {
			Thread.sleep(50);
		}
		engineA = SqlBenchHelper.createEngine(1, a);
		engineB = SqlBenchHelper.createEngine(1, b);
		// INSERT path is proposer-only (engineA); peer applies via OpLog, not local DDL CREATE.
		SqlBenchHelper.ensureKvTable(engineA, TABLE);
	}

	@TearDown
	public void tearDown() {
		if (engineA != null && engineA.catalog().exists(TABLE)) {
			try {
				engineA.catalog().dropTable(TABLE);
			} catch (Exception ignored) {
				// shutting down
			}
		}
		if (engineB != null && engineB.catalog().exists(TABLE)) {
			try {
				engineB.catalog().dropTable(TABLE);
			} catch (Exception ignored) {
				// shutting down
			}
		}
		if (a != null) {
			a.stop();
		}
		if (b != null) {
			b.stop();
		}
	}

	@Benchmark
	public void twoNodeOrchidCommit(Blackhole bh) {
		final long n = seqHint.getAndIncrement();
		final long t0 = System.nanoTime();
		final long affected = engineA.execute(
				"INSERT INTO " + TABLE + " (id, v) VALUES (" + n + ", 'x')").rowsAffected();
		ReplicationMetrics.recordOrchidWaitNs(System.nanoTime() - t0);
		bh.consume(affected);
	}
}
