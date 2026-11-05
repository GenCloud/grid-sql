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
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operator {@code removePeer} must not silently rejoin via HELLO/phase; explicit {@code addPeer} restores.
 * <p>
 * Distinct from partition {@code isolatePeer} (configured quorum retained, anti-solo).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public class PeerOperatorRemoveCharacterizationIT {

	private static final long JOIN_DEADLINE_MS = 5_000L;
	private static final long CLEAR_DEADLINE_MS = 5_000L;
	private static final long POLL_MS = 50L;
	private static final long HELLO_PRESSURE_MS = 750L;
	private static final int SOLO_CONFIGURED_VOTER_COUNT = 1;

	@TempDir
	Path tempDir;

	@Test
	void removePeerWhilePeerAlive_keepsLiveClearAndSolo() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final ReplicationCoordinator a = startNode("orm-a", portA, "orm-b", portB, tempDir.resolve("a"));
		final ReplicationCoordinator b = startNode("orm-b", portB, "orm-a", portA, tempDir.resolve("b"));
		try {
			awaitLiveAtLeast(a, 1, JOIN_DEADLINE_MS);
			a.removePeer("orm-b");
			// B still running: HELLO / phase / catch-up must not resurrect live or quorum.
			Thread.sleep(HELLO_PRESSURE_MS);
			assertEquals(0, a.getOrchidNode().livePeerCount(), "live must stay clear under HELLO pressure after remove");
			assertEquals(SOLO_CONFIGURED_VOTER_COUNT, a.getOrchidNode().configuredVoterCount(),
					"configured quorum must stay solo (no auto-addPeer)");
			a.ensureOrchidSynced();
		} finally {
			a.stop();
			b.stop();
		}
	}

	@Test
	void removePeerThenExplicitAddPeer_restoresLive() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final ReplicationCoordinator a = startNode("orm2-a", portA, "orm2-b", portB, tempDir.resolve("a2"));
		final ReplicationCoordinator b = startNode("orm2-b", portB, "orm2-a", portA, tempDir.resolve("b2"));
		try {
			awaitLiveAtLeast(a, 1, JOIN_DEADLINE_MS);
			a.removePeer("orm2-b");
			awaitLiveEquals(a, 0, CLEAR_DEADLINE_MS);
			a.ensureOrchidSynced();

			final int portB2 = freePort();
			final ReplicationCoordinator b2 = startNode("orm2-b", portB2, "orm2-a", portA, tempDir.resolve("b2r"));
			try {
				a.addPeer(new ReplicationPeer("orm2-b", "127.0.0.1", portB2, "dc-a"));
				awaitLiveAtLeast(a, 1, JOIN_DEADLINE_MS);
				assertTrue(a.getOrchidNode().configuredVoterCount() > SOLO_CONFIGURED_VOTER_COUNT);
			} finally {
				b2.stop();
			}
		} finally {
			a.stop();
			b.stop();
		}
	}

	@Test
	void isolatePeer_retainsConfiguredQuorumAndBlocksSolo() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final ReplicationCoordinator a = startNode("iso-a", portA, "iso-b", portB, tempDir.resolve("ia"));
		final ReplicationCoordinator b = startNode("iso-b", portB, "iso-a", portA, tempDir.resolve("ib"));
		try {
			awaitLiveAtLeast(a, 1, JOIN_DEADLINE_MS);
			final int votersBefore = a.getOrchidNode().configuredVoterCount();
			a.isolatePeer("iso-b");
			awaitLiveEquals(a, 0, CLEAR_DEADLINE_MS);
			assertEquals(votersBefore, a.getOrchidNode().configuredVoterCount(), "isolate must not shrink quorum");
			assertThrows(OrchidNotSyncedException.class, a::ensureOrchidSynced);
		} finally {
			a.stop();
			b.stop();
		}
	}

	private static ReplicationCoordinator startNode(
			String nodeId,
			int bindPort,
			String peerId,
			int peerPort,
			Path dataDir
	) {
		final GridConfigurationProperties props = ReplTestSupport.props(
				nodeId, "orm-cluster", "dc-a", bindPort, dataDir,
				new ArrayList<>(List.of(ReplTestSupport.peer(peerId, "dc-a", peerPort)))
		);
		props.getReplication().getOrchid().setOrderThreshold(0.5);
		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		return coord;
	}

	private static void awaitLiveAtLeast(ReplicationCoordinator coord, int minLive, long deadlineMs)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + deadlineMs;
		while (System.currentTimeMillis() < deadline && coord.getOrchidNode().livePeerCount() < minLive) {
			Thread.sleep(POLL_MS);
		}
		assertTrue(coord.getOrchidNode().livePeerCount() >= minLive,
				"expected livePeerCount>=" + minLive + " but was " + coord.getOrchidNode().livePeerCount());
	}

	private static void awaitLiveEquals(ReplicationCoordinator coord, int expected, long deadlineMs)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + deadlineMs;
		while (System.currentTimeMillis() < deadline && coord.getOrchidNode().livePeerCount() != expected) {
			Thread.sleep(POLL_MS);
		}
		assertEquals(expected, coord.getOrchidNode().livePeerCount());
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
