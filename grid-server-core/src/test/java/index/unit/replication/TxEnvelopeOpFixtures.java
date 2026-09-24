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
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;

import java.nio.charset.StandardCharsets;

/**
 * Shared OpLog TX marker / mutation builders for envelope and TX-aware tests.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class TxEnvelopeOpFixtures {
	private TxEnvelopeOpFixtures() {
	}

	public static ReplicationOp begin(String domain, int shard, long seq, long txId, byte[] value) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				domain, shard, seq, ReplicationOpType.TX_BEGIN,
				Long.toHexString(txId).getBytes(StandardCharsets.UTF_8), value, 1L, 0L));
	}

	public static ReplicationOp commit(String domain, int shard, long seq, long txId) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				domain, shard, seq, ReplicationOpType.TX_COMMIT,
				Long.toHexString(txId).getBytes(StandardCharsets.UTF_8), null, 1L, 0L));
	}

	public static ReplicationOp abort(String domain, int shard, long seq, long txId) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				domain, shard, seq, ReplicationOpType.TX_ABORT,
				Long.toHexString(txId).getBytes(StandardCharsets.UTF_8), null, 1L, 0L));
	}

	public static ReplicationOp upsert(String domain, int shard, long seq, byte[] key, byte[] value) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				domain, shard, seq, ReplicationOpType.UPSERT, key, value, 1L, 0L));
	}

	public static ReplicationOp marker(String domain, int shard, long seq, ReplicationOpType type) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				domain, shard, seq, type, new byte[]{1}, null, 1L, 0L));
	}

	public static ReplicationOp op(String domain, int shard, long seq, ReplicationOpType type,
	                               byte[] key, byte[] value) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				domain, shard, seq, type, key, value, 1L, 0L));
	}
}