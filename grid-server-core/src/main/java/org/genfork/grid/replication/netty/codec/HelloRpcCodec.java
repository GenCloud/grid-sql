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
import org.genfork.grid.replication.netty.ReplicationRpcCodec.Hello;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * HELLO handshake encode/decode (identity + schema + Multi-DC region fence).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class HelloRpcCodec {
	/** Trailing Multi-DC region fence after {@code schemaEpoch} on HELLO (optional for legacy peers). */
	private static final int HELLO_REGION_EPOCH_BYTES = Long.BYTES;
	private static final int HELLO_REGION_ROLE_BYTES = Byte.BYTES;
	/** Legacy HELLO without region fields reports epoch {@code 0} / role {@code 0}. */
	private static final long HELLO_LEGACY_REGION_EPOCH = 0L;
	private static final byte HELLO_LEGACY_REGION_ROLE = 0;

	private HelloRpcCodec() {
	}

	public static byte[] encodeHello(Hello hello) {
		final byte[] nodeId = hello.nodeId().getBytes(StandardCharsets.UTF_8);
		final byte[] clusterId = hello.clusterId().getBytes(StandardCharsets.UTF_8);
		final byte[] localDc = hello.localDc().getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + nodeId.length
						+ Integer.BYTES + clusterId.length
						+ Integer.BYTES + localDc.length
						+ Long.BYTES
						+ HELLO_REGION_EPOCH_BYTES
						+ HELLO_REGION_ROLE_BYTES);
		EncodeBuffers.putLengthPrefixed(buf, nodeId);
		EncodeBuffers.putLengthPrefixed(buf, clusterId);
		EncodeBuffers.putLengthPrefixed(buf, localDc);
		buf.putLong(hello.schemaEpoch());
		buf.putLong(hello.regionEpoch());
		buf.put(hello.regionRole());
		return EncodeBuffers.toByteArray(buf);
	}

	public static Hello decodeHello(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final String nodeId = EncodeBuffers.getUtf8(buf);
		final String clusterId = EncodeBuffers.getUtf8(buf);
		final String localDc = EncodeBuffers.getUtf8(buf);
		final long schemaEpoch = buf.getLong();
		long regionEpoch = HELLO_LEGACY_REGION_EPOCH;
		byte regionRole = HELLO_LEGACY_REGION_ROLE;
		if (buf.remaining() >= HELLO_REGION_EPOCH_BYTES) {
			regionEpoch = buf.getLong();
		}
		if (buf.remaining() >= HELLO_REGION_ROLE_BYTES) {
			regionRole = buf.get();
		}
		return new Hello(nodeId, clusterId, localDc, schemaEpoch, regionEpoch, regionRole);
	}
}
