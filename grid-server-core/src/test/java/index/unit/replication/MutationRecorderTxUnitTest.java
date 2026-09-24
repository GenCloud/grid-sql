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
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.mem.stage.GridEntriesProcessor.AddEntry;
import org.genfork.grid.mem.stage.GridEntriesProcessor.Entry;
import org.genfork.grid.replication.MutationRecorder;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TX unit: multi-op keeps BEGIN+data+COMMIT; single-op skips markers (autocommit).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class MutationRecorderTxUnitTest {
	@TempDir
	Path tempDir;

	private ReplicationCoordinator coordinator;
	private MutationRecorder recorder;

	@BeforeEach
	void setUp() throws Exception {
		int port;
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			port = s.getLocalPort();
		}
		final GridConfigurationProperties props =
				ReplTestSupport.props("tx-unit", "tx-unit", "dc-a", port, tempDir, List.of());
		props.getReplication().getOpLog().setFsync(false);
		coordinator = new ReplicationCoordinator(props);
		coordinator.start();
		final long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline && !coordinator.getOrchidNode().isSynced()) {
			Thread.sleep(10);
		}
		assertTrue(coordinator.getOrchidNode().isSynced());
		final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		recorder = coordinator.registerDomain("tx_unit", ReplTestSupport.singleShard(proc), true);
	}

	@AfterEach
	void tearDown() {
		if (coordinator != null) {
			coordinator.stop();
		}
	}

	@Test
	void singleOpSkipsMarkers() {
		final long before = coordinator.getOrchidNode().getLastCommittedSeq();
		final List<Entry> entries = List.of(
				new AddEntry(null, new byte[]{1, 2, 3}, new byte[]{9, 9, 9})
		);
		recorder.recordTxUnitBlocking(0, 0xABCDEL, null, entries);
		final long after = coordinator.getOrchidNode().getLastCommittedSeq();
		assertEquals(before + 1L, after);
		final List<ReplicationOp> ops = coordinator.getOpLog().readFrom("tx_unit", 0, before + 1L, 10);
		assertEquals(1, ops.size());
		assertEquals(ReplicationOpType.UPSERT, ops.get(0).type());
	}

	@Test
	void multiOpPersistsBeginDataCommitContiguously() {
		final long before = coordinator.getOrchidNode().getLastCommittedSeq();
		final List<Entry> entries = List.of(
				new AddEntry(null, new byte[]{1}, new byte[]{10}),
				new AddEntry(null, new byte[]{2}, new byte[]{20})
		);
		recorder.recordTxUnitBlocking(0, 0xABCDEL, null, entries);
		final long after = coordinator.getOrchidNode().getLastCommittedSeq();
		assertEquals(before + 4L, after);
		final List<ReplicationOp> ops = coordinator.getOpLog().readFrom("tx_unit", 0, before + 1L, 10);
		assertEquals(4, ops.size());
		assertEquals(ReplicationOpType.TX_BEGIN, ops.get(0).type());
		assertEquals(ReplicationOpType.UPSERT, ops.get(1).type());
		assertEquals(ReplicationOpType.UPSERT, ops.get(2).type());
		assertEquals(ReplicationOpType.TX_COMMIT, ops.get(3).type());
	}
}
