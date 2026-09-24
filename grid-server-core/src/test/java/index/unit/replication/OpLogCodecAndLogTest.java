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

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class OpLogCodecAndLogTest {

	@TempDir
	Path tempDir;

	@Test
	void roundTripOpAndSegment() {
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.Domain",
				1,
				1L,
				ReplicationOpType.UPSERT,
				"key-1".getBytes(StandardCharsets.UTF_8),
				"value-1".getBytes(StandardCharsets.UTF_8),
				7L,
				0L
		));
		final byte[] encoded = OpLogCodec.encodeOp(op);
		final ReplicationOp decoded = OpLogCodec.decodeOp(encoded);
		assertEquals(op.opSeq(), decoded.opSeq());
		assertEquals(op.domainType(), decoded.domainType());
		assertEquals(op.checksum(), decoded.checksum());

		final OpLogSegment segment = new OpLogSegment(
				op.domainType(), op.shard(), op.opSeq(), op.opSeq(), List.of(op), OpLogCodec.segmentChecksum(List.of(op))
		);
		final OpLogSegment decodedSegment = OpLogCodec.decodeSegment(OpLogCodec.encodeSegment(segment));
		assertEquals(1, decodedSegment.ops().size());
		assertEquals(op.opSeq(), decodedSegment.ops().getFirst().opSeq());
	}

	@Test
	void checksumMismatchDetected() {
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"demo.Domain", 0, 1L, ReplicationOpType.DELETE,
				"k".getBytes(StandardCharsets.UTF_8), null, 1L, 0L
		));
		final byte[] encoded = OpLogCodec.encodeOp(op);
		encoded[encoded.length - 1] ^= 0x01;
		assertThrows(IllegalStateException.class, () -> OpLogCodec.decodeOp(encoded));
	}

	@Test
	void opLogAppendTruncateIdempotent() throws Exception {
		try (OpLog log = new OpLog(tempDir.resolve("oplog"), false)) {
			final ReplicationOp op1 = OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, 1L, 0L
			));
			final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
					"d", 0, 2L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{3}, 1L, 0L
			));
			log.append(op1);
			log.append(op2);
			log.append(op2);
			assertEquals(2, log.size("d", 0));
			log.truncateTo("d", 0, 1);
			assertEquals(1, log.size("d", 0));
			assertEquals(2L, log.lastSeq("d", 0));
			assertTrue(log.readFrom("d", 0, 2, 10).size() == 1);
		}
	}
}
