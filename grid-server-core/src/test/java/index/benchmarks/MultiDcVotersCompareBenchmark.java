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
import index.unit.replication.DelayedTcpProxy;
import index.unit.replication.ReplTestSupport;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.sql.SqlEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local ASYNC_SHIP (N=1 local) vs SYNC_VOTERS_ACROSS_DC commit latency via SqlEngine INSERT.
 * Optional delayMs routes the remote peer through {@link DelayedTcpProxy} (WAN RTT stand-in).
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
public class MultiDcVotersCompareBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "mdc";

	@Param({"ASYNC_LOCAL", "SYNC_VOTERS"})
	public String mode;

	/** One-way proxy delay in ms; only applied for SYNC_VOTERS. */
	@Param({"0", "10"})
	public int delayMs;

	private ReplicationCoordinator local;
	private ReplicationCoordinator remote;
	private DelayedTcpProxy proxy;
	private SqlEngine engine;
	private SqlEngine remoteEngine;
	private final AtomicLong seqHint = new AtomicLong(1);

	@Setup
	public void setup() throws Exception {
		final Path root = Files.createTempDirectory("jmh-mdc");
		final int portA = freePort();
		final int portB = freePort();

		if ("SYNC_VOTERS".equals(mode)) {
			final int peerPort;
			if (delayMs > 0) {
				proxy = new DelayedTcpProxy("127.0.0.1", portB, delayMs);
				peerPort = proxy.localPort();
			} else {
				peerPort = portB;
			}
			final GridConfigurationProperties propsA = ReplTestSupport.props(
					"jmh-a", "jmh-mdc", "dc-a", portA, root.resolve("a"),
					List.of(ReplTestSupport.peer("jmh-b", "dc-b", peerPort))
			);
			propsA.getReplication().getCrossDc().setMode(CrossDcMode.SYNC_VOTERS_ACROSS_DC.name());
			propsA.getReplication().getCrossDc().setVoters(List.of("jmh-b"));
			propsA.getReplication().getCrossDc().setPhaseCoupling(false);
			propsA.getReplication().getCrossDc().setRemoteAckTimeoutMs(5_000L);
			propsA.getReplication().getOpLog().setFsync(false);

			final GridConfigurationProperties propsB = ReplTestSupport.props(
					"jmh-b", "jmh-mdc", "dc-b", portB, root.resolve("b"),
					List.of(ReplTestSupport.peer("jmh-a", "dc-a", portA))
			);
			propsB.getReplication().getCrossDc().setMode(CrossDcMode.ASYNC_SHIP.name());
			propsB.getReplication().getCrossDc().setPhaseCoupling(false);
			propsB.getReplication().getOpLog().setFsync(false);

			local = new ReplicationCoordinator(propsA);
			remote = new ReplicationCoordinator(propsB);
			local.start();
			remote.start();
			waitPeers(local, remote, 10_000);
			Thread.sleep(150);
			engine = SqlBenchHelper.createEngine(1, local);
			remoteEngine = SqlBenchHelper.createEngine(1, remote);
			SqlBenchHelper.ensureKvTable(engine, TABLE);
			SqlBenchHelper.ensureKvTable(remoteEngine, TABLE);
		} else {
			if (delayMs != 0) {
				// ASYNC_LOCAL ignores delay; keep one JMH combo for baseline.
			}
			final GridConfigurationProperties propsA = ReplTestSupport.props(
					"jmh-solo", "jmh-mdc", "dc-a", portA, root.resolve("solo"), List.of()
			);
			propsA.getReplication().getCrossDc().setMode(CrossDcMode.ASYNC_SHIP.name());
			propsA.getReplication().getOpLog().setFsync(false);
			local = new ReplicationCoordinator(propsA);
			local.start();
			final long deadline = System.currentTimeMillis() + 3_000;
			while (System.currentTimeMillis() < deadline && !local.getOrchidNode().isSynced()) {
				Thread.sleep(10);
			}
			engine = SqlBenchHelper.createEngine(1, local);
			SqlBenchHelper.ensureKvTable(engine, TABLE);
		}
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
		if (remoteEngine != null && remoteEngine.catalog().exists(TABLE)) {
			try {
				remoteEngine.catalog().dropTable(TABLE);
			} catch (Exception ignored) {
				// shutting down
			}
		}
		if (local != null) {
			local.stop();
		}
		if (remote != null) {
			remote.stop();
		}
		if (proxy != null) {
			proxy.close();
		}
	}

	@Benchmark
	public void orchidCommit(Blackhole bh) {
		final long n = seqHint.getAndIncrement();
		bh.consume(engine.execute(
				"INSERT INTO " + TABLE + " (id, v) VALUES (" + n + ", 'xxxx')").rowsAffected());
	}

	private static void waitPeers(ReplicationCoordinator a, ReplicationCoordinator b, long timeoutMs)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.getOrchidNode().livePeerCount() >= 1
					&& b.getOrchidNode().livePeerCount() >= 1
					&& a.getOrchidNode().isSynced()) {
				return;
			}
			Thread.sleep(50);
		}
		throw new IllegalStateException("peers not ready for SYNC_VOTERS bench");
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
