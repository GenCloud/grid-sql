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
package index.unit.replication.chaos;

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.MutationRecorder;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process 3-node latency: MutationRecorder commit + async ship visibility on peers.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ThreeNodeClusterLatencyIT {

	private static final int OPS = 50;
	private static final String DOMAIN = "chaos.Latency3";

	@TempDir
	Path tempDir;

	@Test
	void measureCommitAndReplicaVisibility() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final int portC = freePort();
		final var propsA = ReplTestSupport.safetyProps(
				"lat-a", "lat3", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("lat-b", "dc-a", portB), ReplTestSupport.peer("lat-c", "dc-a", portC))
		);
		propsA.getReplication().getCrossDc().setBatchMaxOps(1);
		propsA.getReplication().getCrossDc().setBatchMaxWaitMs(1);
		final var propsB = ReplTestSupport.safetyProps(
				"lat-b", "lat3", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("lat-a", "dc-a", portA), ReplTestSupport.peer("lat-c", "dc-a", portC))
		);
		final var propsC = ReplTestSupport.safetyProps(
				"lat-c", "lat3", "dc-a", portC, tempDir.resolve("c"),
				List.of(ReplTestSupport.peer("lat-a", "dc-a", portA), ReplTestSupport.peer("lat-b", "dc-a", portB))
		);

		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procC = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		final ReplicationCoordinator c = new ReplicationCoordinator(propsC);
		final List<Double> commitUs = new ArrayList<>();
		final List<Double> visBMs = new ArrayList<>();
		final List<Double> visCMs = new ArrayList<>();
		int missB = 0;
		int missC = 0;
		try {
			final MutationRecorder recA = a.registerDomain(DOMAIN, ReplTestSupport.singleShard(procA), true);
			b.registerDomain(DOMAIN, ReplTestSupport.singleShard(procB), true);
			c.registerDomain(DOMAIN, ReplTestSupport.singleShard(procC), true);
			a.start();
			b.start();
			c.start();

			final long deadline = System.currentTimeMillis() + 20_000;
			while (System.currentTimeMillis() < deadline
					&& (!a.getOrchidNode().isSynced() || !b.getOrchidNode().isSynced() || !c.getOrchidNode().isSynced()
					|| !a.getOrchidNode().isPhaseRankedProposer())) {
				Thread.sleep(50);
			}
			assertTrue(a.getOrchidNode().isPhaseRankedProposer(), "lat-a should be proposer");

			for (int i = 0; i < OPS; i++) {
				final byte[] key = new byte[]{(byte) (i + 1), 7, (byte) (i >> 8)};
				final byte[] value = new byte[]{(byte) i, 9, 9, 9};
				final long t0 = System.nanoTime();
				recA.recordCommittedBlocking(0, new GridEntriesProcessor.AddEntry(null, key, value));
				procA.installCommitted(key, value, false);
				commitUs.add((System.nanoTime() - t0) / 1_000.0);

				final long v0 = System.nanoTime();
				boolean seenB = false;
				boolean seenC = false;
				for (int p = 0; p < 400; p++) {
					if (!seenB && procB.get(key) != null) {
						visBMs.add((System.nanoTime() - v0) / 1_000_000.0);
						seenB = true;
					}
					if (!seenC && procC.get(key) != null) {
						visCMs.add((System.nanoTime() - v0) / 1_000_000.0);
						seenC = true;
					}
					if (seenB && seenC) {
						break;
					}
					Thread.sleep(5);
				}
				if (!seenB) {
					missB++;
				}
				if (!seenC) {
					missC++;
				}
			}

			Collections.sort(commitUs);
			Collections.sort(visBMs);
			Collections.sort(visCMs);
			final double commitP50 = pct(commitUs, 0.50);
			final double commitP99 = pct(commitUs, 0.99);
			final double visBp50 = pct(visBMs, 0.50);
			final double visCp50 = pct(visCMs, 0.50);

			final Path out = Path.of("benchmarks", "results");
			Files.createDirectories(out);
			final String stamp = System.getenv().getOrDefault("JMH_STAMP", "2026-09-14-full");
			final String json = "{"
					+ "\"stamp\":\"" + stamp + "\","
					+ "\"system\":\"jamoa-grid\","
					+ "\"mode\":\"in-process-3node\","
					+ "\"ops\":" + OPS + ","
					+ "\"commit_p50_us\":" + commitP50 + ","
					+ "\"commit_p99_us\":" + commitP99 + ","
					+ "\"visibility_b_p50_ms\":" + visBp50 + ","
					+ "\"visibility_c_p50_ms\":" + visCp50 + ","
					+ "\"miss_b\":" + missB + ","
					+ "\"miss_c\":" + missC
					+ "}";
			Files.writeString(out.resolve(stamp + "-cluster-3node-inprocess.json"), json);
			System.out.printf(
					"3node latency commit_p50=%.1f us p99=%.1f us visB_p50=%.1f ms visC_p50=%.1f ms miss=%d/%d%n",
					commitP50, commitP99, visBp50, visCp50, missB, missC
			);
			assertTrue(commitP50 > 0);
			assertTrue(missB + missC < OPS, "too many visibility misses");
		} finally {
			try { a.stop(); } catch (Exception ignored) {}
			try { b.stop(); } catch (Exception ignored) {}
			try { c.stop(); } catch (Exception ignored) {}
		}
	}

	private static double pct(List<Double> xs, double p) {
		if (xs.isEmpty()) {
			return Double.NaN;
		}
		return xs.get(Math.min(xs.size() - 1, (int) Math.floor((xs.size() - 1) * p)));
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}