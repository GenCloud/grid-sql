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
package org.genfork.grid.replication.schema;

import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * schemaEpoch layout version helpers + BARRIER / SNAPSHOT migration markers.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class SchemaEpochSupport {
	private SchemaEpochSupport() {
	}

	public static long layoutHash(Class<?> domainType) {
		final CRC32 crc = new CRC32();
		crc.update(domainType.getName().getBytes(StandardCharsets.UTF_8));
		return crc.getValue();
	}

	public static void refuseIfMismatch(long localEpoch, long remoteEpoch) {
		if (localEpoch != remoteEpoch) {
			throw new IllegalStateException("schemaEpoch mismatch local=" + localEpoch + " remote=" + remoteEpoch);
		}
	}

	public static ReplicationOp barrier(OpLog opLog, ReplicationNodeState state,
	                                    String domainType, int shard, long layoutHash) {
		final long seq = Math.max(1L, opLog.lastSeq(domainType, shard) + 1);
		final byte[] key = layoutHashBytes(layoutHash);
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				domainType, shard, seq, ReplicationOpType.BARRIER, key, null, state.getSchemaEpoch(), 0L
		));
		opLog.append(op);
		return op;
	}

	public static byte[] layoutHashBytes(long v) {
		final byte[] b = new byte[8];
		for (int i = 7; i >= 0; i--) {
			b[i] = (byte) (v & 0xFF);
			v >>>= 8;
		}
		return b;
	}

	public static ReplicationOp snapshotMarker(OpLog opLog, ReplicationNodeState state,
	                                           String domainType, int shard, long layoutHash) {
		final long seq = Math.max(1L, opLog.lastSeq(domainType, shard) + 1);
		final byte[] key = layoutHashBytes(layoutHash);
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				domainType, shard, seq, ReplicationOpType.SNAPSHOT_MARKER, key, null, state.getSchemaEpoch(), 0L
		));
		opLog.append(op);
		return op;
	}
}
