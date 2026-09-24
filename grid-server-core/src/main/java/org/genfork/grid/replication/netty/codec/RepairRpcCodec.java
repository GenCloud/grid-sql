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
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.OplogPull;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.RepairReply;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.RepairRequest;
import org.genfork.grid.replication.repair.RepairCommand;
import org.genfork.grid.replication.repair.RepairCommandType;
import org.genfork.grid.replication.repair.VersionLocus;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REPAIR_REQUEST / REPAIR_REPLY / OPLOG_PULL encode/decode.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class RepairRpcCodec {
	private RepairRpcCodec() {
	}

	public static byte[] encodeRepairRequest(String fromNodeId, String domainType, int shard,
	                                         Map<Long, VersionLocus> view) {
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final byte[] domain = domainType.getBytes(StandardCharsets.UTF_8);
		int lociBytes = 0;
		final List<byte[]> encoded = new ArrayList<>();
		for (VersionLocus locus : view.values()) {
			final ByteBuffer one = EncodeBuffers.allocateWireLe(
					Long.BYTES + Long.BYTES + Long.BYTES + Long.BYTES);
			one.putLong(locus.keyHash());
			one.putLong(locus.opSeq());
			one.putLong(locus.schemaEpoch());
			one.putLong(locus.contentChecksum());
			final byte[] locusBytes = EncodeBuffers.toByteArray(one);
			encoded.add(locusBytes);
			lociBytes += locusBytes.length;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + from.length + Integer.BYTES + domain.length
						+ Integer.BYTES + Integer.BYTES + lociBytes);
		EncodeBuffers.putLengthPrefixed(buf, from);
		EncodeBuffers.putLengthPrefixed(buf, domain);
		buf.putInt(shard);
		buf.putInt(encoded.size());
		for (byte[] e : encoded) {
			buf.put(e);
		}
		return EncodeBuffers.toByteArray(buf);
	}

	public static RepairRequest decodeRepairRequest(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final String from = EncodeBuffers.getUtf8(buf);
		final String domain = EncodeBuffers.getUtf8(buf);
		final int shard = buf.getInt();
		final int count = buf.getInt();
		final Map<Long, VersionLocus> view = new HashMap<>();
		for (int i = 0; i < count; i++) {
			final long keyHash = buf.getLong();
			final long opSeq = buf.getLong();
			final long epoch = buf.getLong();
			final long checksum = buf.getLong();
			view.put(keyHash, new VersionLocus(domain, shard, keyHash, opSeq, epoch, checksum));
		}
		return new RepairRequest(from, domain, shard, view);
	}

	public static byte[] encodeRepairReply(String fromNodeId, List<RepairCommand> commands, List<ReplicationOp> ops) {
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final List<byte[]> cmdBytes = new ArrayList<>();
		int cmdSize = 0;
		for (RepairCommand c : commands) {
			final byte[] domain = c.domainType().getBytes(StandardCharsets.UTF_8);
			final ByteBuffer one = EncodeBuffers.allocateWireLe(
					Byte.BYTES + Integer.BYTES + domain.length
							+ Integer.BYTES + Long.BYTES + Long.BYTES + Long.BYTES + Long.BYTES);
			one.put((byte) c.type().ordinal());
			EncodeBuffers.putLengthPrefixed(one, domain);
			one.putInt(c.shard());
			one.putLong(c.keyHash());
			one.putLong(c.localOpSeq());
			one.putLong(c.remoteOpSeq());
			one.putLong(c.remoteChecksum());
			final byte[] cmdEncoded = EncodeBuffers.toByteArray(one);
			cmdBytes.add(cmdEncoded);
			cmdSize += Integer.BYTES + cmdEncoded.length;
		}
		final List<byte[]> opBytes = new ArrayList<>();
		int opSize = 0;
		for (ReplicationOp op : ops) {
			final byte[] encoded = OpLogCodec.encodeOp(op);
			opBytes.add(encoded);
			opSize += Integer.BYTES + encoded.length;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + from.length + Integer.BYTES + cmdSize + Integer.BYTES + opSize);
		EncodeBuffers.putLengthPrefixed(buf, from);
		buf.putInt(cmdBytes.size());
		for (byte[] c : cmdBytes) {
			buf.putInt(c.length);
			buf.put(c);
		}
		buf.putInt(opBytes.size());
		for (byte[] o : opBytes) {
			buf.putInt(o.length);
			buf.put(o);
		}
		return EncodeBuffers.toByteArray(buf);
	}

	public static RepairReply decodeRepairReply(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final String from = EncodeBuffers.getUtf8(buf);
		final int cmdCount = buf.getInt();
		final List<RepairCommand> commands = new ArrayList<>(cmdCount);
		for (int i = 0; i < cmdCount; i++) {
			final int len = buf.getInt();
			final byte[] raw = new byte[len];
			buf.get(raw);
			final ByteBuffer one = EncodeBuffers.wrapLe(raw);
			final RepairCommandType type = RepairCommandType.values()[one.get() & 0xFF];
			final String domain = EncodeBuffers.getUtf8(one);
			commands.add(new RepairCommand(type, domain, one.getInt(), one.getLong(),
					one.getLong(), one.getLong(), one.getLong()));
		}
		final int opCount = buf.getInt();
		final List<ReplicationOp> ops = new ArrayList<>(opCount);
		for (int i = 0; i < opCount; i++) {
			final int len = buf.getInt();
			final byte[] raw = new byte[len];
			buf.get(raw);
			ops.add(OpLogCodec.decodeOp(raw));
		}
		return new RepairReply(from, commands, ops);
	}

	public static byte[] encodeOplogPull(String fromNodeId, String domainType, int shard, long fromSeqInclusive) {
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final byte[] domain = domainType.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + from.length + Integer.BYTES + domain.length + Integer.BYTES + Long.BYTES);
		EncodeBuffers.putLengthPrefixed(buf, from);
		EncodeBuffers.putLengthPrefixed(buf, domain);
		buf.putInt(shard);
		buf.putLong(fromSeqInclusive);
		return EncodeBuffers.toByteArray(buf);
	}

	public static OplogPull decodeOplogPull(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		return new OplogPull(EncodeBuffers.getUtf8(buf), EncodeBuffers.getUtf8(buf), buf.getInt(), buf.getLong());
	}
}
