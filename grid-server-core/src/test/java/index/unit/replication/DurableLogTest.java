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

import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.orchid.FileDurableOrchidStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class DurableLogTest {

	@TempDir
	Path tempDir;

	@Test
	void opLogSurvivesRestartAndSeedsWatermarks() {
		final Path dir = tempDir.resolve("node-a");
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"d", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
		));
		try (OpLog log = new OpLog(dir, false)) {
			log.append(op);
			assertEquals(1, log.size("d", 0));
		}
		try (OpLog reloaded = new OpLog(dir, false)) {
			assertEquals(1, reloaded.size("d", 0));
			assertEquals(1L, reloaded.lastSeq("d", 0));
			assertEquals(2, reloaded.readFrom("d", 0, 1, 10).getFirst().value()[0]);
			final ReplicationNodeState state = new ReplicationNodeState("n", "c", "dc-a", 1L);
			state.seedAllFromOpLog(reloaded);
			// Seed aligns nextOpSeq only; applied stays 0 until map hydrate.
			assertEquals(0L, state.appliedWatermark("d", 0));
			assertTrue(state.nextOpSeq("d", 0) >= 2L);
		}
	}

	@Test
	void orchidStateSurvivesRestart() throws Exception {
		final Path dir = tempDir.resolve("orchid-node");
		try (FileDurableOrchidStore store = new FileDurableOrchidStore(dir, false)) {
			store.storeLastCommittedSeq(42L);
			assertEquals(42L, store.loadLastCommittedSeq());
		}
		try (FileDurableOrchidStore reloaded = new FileDurableOrchidStore(dir, false)) {
			assertEquals(42L, reloaded.loadLastCommittedSeq());
		}
	}
}
