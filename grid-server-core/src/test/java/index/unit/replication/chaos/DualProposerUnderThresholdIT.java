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
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dual proposer under impossible R threshold: no orphan map row until sync possible.
 */
public class DualProposerUnderThresholdIT {

	@TempDir
	Path tempDir;

	@Test
	void dualProposerBlockedLeavesNoOrphan() throws Exception {
		final int portA = freePort();
		final int portB = freePort();
		final var aProps = ReplTestSupport.props(
				"dual-a", "dual", "dc-a", portA, tempDir.resolve("a"),
				new ArrayList<>(List.of(ReplTestSupport.peer("dual-b", "dc-a", portB)))
		);
		aProps.getReplication().getOrchid().setOrderThreshold(1.01);
		final var bProps = ReplTestSupport.props(
				"dual-b", "dual", "dc-a", portB, tempDir.resolve("b"),
				new ArrayList<>(List.of(ReplTestSupport.peer("dual-a", "dc-a", portA)))
		);
		bProps.getReplication().getOrchid().setOrderThreshold(1.01);

		final ReplicationCoordinator a = new ReplicationCoordinator(aProps);
		final ReplicationCoordinator b = new ReplicationCoordinator(bProps);
		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		a.registerDomain("chaos.Dual", ReplTestSupport.singleShard(procA), true);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		b.registerDomain("chaos.Dual", ReplTestSupport.singleShard(procB), true);
		a.start();
		b.start();

		final long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline && a.getOrchidNode().livePeerCount() < 1) {
			Thread.sleep(50);
		}
		assertTrue(a.getOrchidNode().livePeerCount() >= 1);
		assertThrows(OrchidNotSyncedException.class, a::ensureOrchidSynced);

		final byte[] key = new byte[]{9, 9};
		final byte[] value = new byte[]{1};
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"chaos.Dual", 0, 1L, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		assertThrows(CompletionException.class, () -> a.getOrchidNode().appendAndWaitCommit(op).join());
		assertNull(procA.get(key));

		a.stop();
		b.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
