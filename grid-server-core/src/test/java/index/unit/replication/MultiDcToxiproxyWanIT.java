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
package index.unit.replication;

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SYNC_VOTERS_ACROSS_DC over a delayed TCP path (in-process toxiproxy stand-in).
 * Compose twin: benchmarks/compare/docker-compose.toxiproxy.yml (profile wan).
 */
public class MultiDcToxiproxyWanIT {

	@TempDir
	Path tempDir;

	@Test
	void latencyIncreasesCommitAndPartitionFailClosed() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final long wanDelayMs = 40L;

		// Delay both directions: inbound HELLO replaces outbound channel in channelsByPeer,
		// so a one-sided proxy is bypassed when the reverse peer dials direct.
		try (DelayedTcpProxy proxyToB = new DelayedTcpProxy("127.0.0.1", portB, wanDelayMs);
		     DelayedTcpProxy proxyToA = new DelayedTcpProxy("127.0.0.1", portA, wanDelayMs)) {
			final GridConfigurationProperties propsB = ReplTestSupport.props(
					"wan-b", "wan-mdc", "dc-b", portB, tempDir.resolve("b"),
					List.of(ReplTestSupport.peer("wan-a", "dc-a", proxyToA.localPort()))
			);
			propsB.getReplication().getCrossDc().setMode(CrossDcMode.ASYNC_SHIP.name());
			propsB.getReplication().getCrossDc().setPhaseCoupling(false);

			final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
			b.start();
			try {
				final GridConfigurationProperties propsA = ReplTestSupport.props(
						"wan-a", "wan-mdc", "dc-a", portA, tempDir.resolve("a"),
						List.of(ReplTestSupport.peer("wan-b", "dc-b", proxyToB.localPort()))
				);
				propsA.getReplication().getCrossDc().setMode(CrossDcMode.SYNC_VOTERS_ACROSS_DC.name());
				propsA.getReplication().getCrossDc().setVoters(List.of("wan-b"));
				propsA.getReplication().getCrossDc().setPhaseCoupling(false);
				propsA.getReplication().getCrossDc().setRemoteAckTimeoutMs(4_000L);

				final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
				a.start();
				try {
					waitPeers(a, b, 12_000);
					Thread.sleep(200);

					final List<Long> samples = new ArrayList<>();
					for (int i = 1; i <= 5; i++) {
						final long n = i;
						final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
								"demo.Wan", 0, n, ReplicationOpType.UPSERT,
								new byte[]{(byte) n}, new byte[]{1}, 1L, 0L
						));
						final long t0 = System.nanoTime();
						final long seq = a.getOrchidNode().appendAndWaitCommit(op).get(8, TimeUnit.SECONDS);
						samples.add((System.nanoTime() - t0) / 1_000_000L);
						assertTrue(seq >= 1);
						a.getOrchidNode().confirmPersisted(seq);
					}
					samples.sort(Long::compareTo);
					final long p50 = samples.get(samples.size() / 2);
					assertTrue(p50 >= 30L, "expected WAN delay to inflate commit p50, got " + p50 + "ms samples=" + samples);

					b.stop();
					a.getOrchidNode().forgetPeer("wan-b");

					final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
							"demo.Wan", 0, 99L, ReplicationOpType.UPSERT, new byte[]{9}, new byte[]{2}, 1L, 0L
					));
					final CompletionException failed = assertThrows(CompletionException.class,
							() -> a.getOrchidNode().appendAndWaitCommit(op2).join());
					Throwable cause = failed.getCause() == null ? failed : failed.getCause();
					assertTrue(cause instanceof OrchidNotSyncedException, "got " + cause);
					assertTrue(cause.getMessage().contains("remote voter digest timeout"), cause.getMessage());
				} finally {
					a.stop();
				}
			} finally {
				if (b.getOrchidNode() != null) {
					try {
						b.stop();
					} catch (Exception ignored) {
					}
				}
			}
		}
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
		throw new IllegalStateException("peers not ready");
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}