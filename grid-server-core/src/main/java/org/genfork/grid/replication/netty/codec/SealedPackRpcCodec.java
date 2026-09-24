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
package org.genfork.grid.replication.netty.codec;

import org.genfork.grid.nio.EncodeBuffers;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.SealedShardPackMsg;
import org.genfork.grid.replication.transport.ReplicationMessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@link ReplicationMessageType#SEALED_SHARD_PACK} encode/decode.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class SealedPackRpcCodec {
	private static final byte[] EMPTY_PACK = new byte[0];

	private SealedPackRpcCodec() {
	}

	public static byte[] encodeSealedShardPack(String fromNodeId, String domainType, int shard, byte[] packed) {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		Objects.requireNonNull(domainType, "domainType");
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final byte[] domain = domainType.getBytes(StandardCharsets.UTF_8);
		final byte[] body = packed == null ? EMPTY_PACK : packed;
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + from.length + Integer.BYTES + domain.length
						+ Integer.BYTES + Integer.BYTES + body.length);
		EncodeBuffers.putLengthPrefixed(buf, from);
		EncodeBuffers.putLengthPrefixed(buf, domain);
		buf.putInt(shard);
		buf.putInt(body.length);
		if (body.length > 0) {
			buf.put(body);
		}
		return EncodeBuffers.toByteArray(buf);
	}

	public static SealedShardPackMsg decodeSealedShardPack(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final String fromNodeId = EncodeBuffers.getUtf8(buf);
		final String domainType = EncodeBuffers.getUtf8(buf);
		final int shard = buf.getInt();
		final int len = buf.getInt();
		if (len < 0 || len > buf.remaining()) {
			throw new IllegalArgumentException("invalid sealed shard pack length: " + len);
		}
		final byte[] packed = new byte[len];
		buf.get(packed);
		return new SealedShardPackMsg(fromNodeId, domainType, shard, packed);
	}
}
