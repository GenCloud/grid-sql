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
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intentional {@code removePeer} shrinks configured quorum (N→1) → solo writes allowed;
 * re-{@code addPeer} restores multi-node sync. Distinct from partition {@code isolatePeer}.
 */
public class PeerLivenessIT {

	@TempDir
	Path tempDir;

	@Test
	void disconnectClearsLiveAndAllowsSolo() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final GridConfigurationProperties aProps = ReplTestSupport.props(
				"live-a", "live-cluster", "dc-a", portA, tempDir.resolve("a"),
				new ArrayList<>(List.of(ReplTestSupport.peer("live-b", "dc-a", portB)))
		);
		aProps.getReplication().getOrchid().setOrderThreshold(0.5);
		final GridConfigurationProperties bProps = ReplTestSupport.props(
				"live-b", "live-cluster", "dc-a", portB, tempDir.resolve("b"),
				new ArrayList<>(List.of(ReplTestSupport.peer("live-a", "dc-a", portA)))
		);
		bProps.getReplication().getOrchid().setOrderThreshold(0.5);

		final ReplicationCoordinator a = new ReplicationCoordinator(aProps);
		final ReplicationCoordinator b = new ReplicationCoordinator(bProps);
		a.start();
		b.start();

		final long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline && a.getOrchidNode().livePeerCount() < 1) {
			Thread.sleep(50);
		}
		assertTrue(a.getOrchidNode().livePeerCount() >= 1);

		a.removePeer("live-b");
		b.stop();
		Thread.sleep(200);

		assertEquals(0, a.getOrchidNode().livePeerCount());
		a.ensureOrchidSynced();

		final int portB2 = freePort();
		final GridConfigurationProperties b2Props = ReplTestSupport.props(
				"live-b", "live-cluster", "dc-a", portB2, tempDir.resolve("b2"),
				new ArrayList<>(List.of(ReplTestSupport.peer("live-a", "dc-a", portA)))
		);
		b2Props.getReplication().getOrchid().setOrderThreshold(0.5);
		final ReplicationCoordinator b2 = new ReplicationCoordinator(b2Props);
		b2.start();
		a.addPeer(new ReplicationPeer("live-b", "127.0.0.1", portB2, "dc-a"));

		final long deadline2 = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline2 && a.getOrchidNode().livePeerCount() < 1) {
			Thread.sleep(50);
		}
		assertTrue(a.getOrchidNode().livePeerCount() >= 1);

		a.stop();
		b2.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
