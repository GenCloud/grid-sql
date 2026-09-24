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
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ApplyAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ApplyNack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * APPLY_ACK / APPLY_NACK / APPLY_ACK_BATCH encode/decode.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class ApplyRpcCodec {
	/** Bytes for one (domain, shard, opSeq) after shared fromNodeId in APPLY_ACK_BATCH. */
	private static final int APPLY_ACK_ENTRY_OVERHEAD = Integer.BYTES + Integer.BYTES + Long.BYTES;

	private ApplyRpcCodec() {
	}

	public static byte[] encodeApplyAck(ApplyAck ack) {
		final byte[] from = ack.fromNodeId().getBytes(StandardCharsets.UTF_8);
		final byte[] domain = ack.domainType().getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + from.length + Integer.BYTES + domain.length + Integer.BYTES + Long.BYTES);
		encodeApplyAckInto(buf, from, domain, ack.shard(), ack.opSeq());
		return EncodeBuffers.toByteArray(buf);
	}

	/**
	 * Encode {@code acks} sharing one {@code fromNodeId} into APPLY_ACK_BATCH body.
	 * Empty list yields fromNodeId + count=0.
	 */
	public static byte[] encodeApplyAckBatch(String fromNodeId, List<ApplyAck> acks) {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		final List<ApplyAck> list = acks == null ? List.of() : acks;
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		int domainBytes = 0;
		final List<byte[]> domains = new ArrayList<>(list.size());
		for (ApplyAck ack : list) {
			final byte[] domain = ack.domainType().getBytes(StandardCharsets.UTF_8);
			domains.add(domain);
			domainBytes += Integer.BYTES + domain.length + APPLY_ACK_ENTRY_OVERHEAD;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + from.length + Integer.BYTES + domainBytes);
		EncodeBuffers.putLengthPrefixed(buf, from);
		buf.putInt(list.size());
		for (int i = 0; i < list.size(); i++) {
			final ApplyAck ack = list.get(i);
			EncodeBuffers.putLengthPrefixed(buf, domains.get(i));
			buf.putInt(ack.shard());
			buf.putLong(ack.opSeq());
		}
		return EncodeBuffers.toByteArray(buf);
	}

	public static List<ApplyAck> decodeApplyAckBatch(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final String from = EncodeBuffers.getUtf8(buf);
		final int count = buf.getInt();
		if (count < 0) {
			throw new IllegalArgumentException("APPLY_ACK_BATCH negative count");
		}
		final List<ApplyAck> acks = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			acks.add(new ApplyAck(from, EncodeBuffers.getUtf8(buf), buf.getInt(), buf.getLong()));
		}
		return acks;
	}

	public static ApplyAck decodeApplyAck(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		return new ApplyAck(EncodeBuffers.getUtf8(buf), EncodeBuffers.getUtf8(buf), buf.getInt(), buf.getLong());
	}

	private static void encodeApplyAckInto(ByteBuffer buf, byte[] from, byte[] domain, int shard, long opSeq) {
		EncodeBuffers.putLengthPrefixed(buf, from);
		EncodeBuffers.putLengthPrefixed(buf, domain);
		buf.putInt(shard);
		buf.putLong(opSeq);
	}

	public static byte[] encodeApplyNack(ApplyNack nack) {
		final byte[] from = nack.fromNodeId().getBytes(StandardCharsets.UTF_8);
		final byte[] domain = nack.domainType().getBytes(StandardCharsets.UTF_8);
		final byte[] reason = nack.reason() == null ? new byte[0] : nack.reason().getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + from.length + Integer.BYTES + domain.length
						+ Integer.BYTES + Long.BYTES + Integer.BYTES + reason.length);
		EncodeBuffers.putLengthPrefixed(buf, from);
		EncodeBuffers.putLengthPrefixed(buf, domain);
		buf.putInt(nack.shard());
		buf.putLong(nack.opSeq());
		EncodeBuffers.putLengthPrefixed(buf, reason);
		return EncodeBuffers.toByteArray(buf);
	}

	public static ApplyNack decodeApplyNack(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		return new ApplyNack(
				EncodeBuffers.getUtf8(buf),
				EncodeBuffers.getUtf8(buf),
				buf.getInt(),
				buf.getLong(),
				EncodeBuffers.getUtf8(buf));
	}
}
