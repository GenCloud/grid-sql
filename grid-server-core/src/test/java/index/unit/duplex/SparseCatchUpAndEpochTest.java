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
package index.unit.duplex;

import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.join.SparseCatchUp;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.schema.SchemaEpochSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public class SparseCatchUpAndEpochTest {

	@TempDir
	Path tempDir;

	@Test
	void catchUpFromWatermark() throws Exception {
		try (OpLog opLog = new OpLog(tempDir.resolve("catchup"), false)) {
			final ReplicationNodeState state = new ReplicationNodeState("n1", "c1", "dc-a", 1L);
			final SparseCatchUp catchUp = new SparseCatchUp(state, opLog, null);

			opLog.append(OpLogCodec.withChecksum(new ReplicationOp(
					"demo", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L)));
			opLog.append(OpLogCodec.withChecksum(new ReplicationOp(
					"demo", 0, 2L, ReplicationOpType.UPSERT, new byte[]{3}, new byte[]{4}, 1L, 0L)));

			final List<ReplicationOp> ops = catchUp.catchUpFromWatermark("demo", 0, 1L);
			assertEquals(1, ops.size());
			assertEquals(2L, ops.getFirst().opSeq());
		}
	}

	@Test
	void schemaEpochBarrier() throws Exception {
		try (OpLog opLog = new OpLog(tempDir.resolve("barrier"), false)) {
			final ReplicationNodeState state = new ReplicationNodeState("n1", "c1", "dc-a", 1L);
			final long hash = SchemaEpochSupport.layoutHash(String.class);
			final ReplicationOp barrier = SchemaEpochSupport.barrier(opLog, state, "demo", 0, hash);
			assertEquals(ReplicationOpType.BARRIER, barrier.type());
			assertEquals(1L, barrier.schemaEpoch());
			SchemaEpochSupport.refuseIfMismatch(1L, 1L);
			assertThrows(IllegalStateException.class, () -> SchemaEpochSupport.refuseIfMismatch(1L, 2L));
		}
	}
}
