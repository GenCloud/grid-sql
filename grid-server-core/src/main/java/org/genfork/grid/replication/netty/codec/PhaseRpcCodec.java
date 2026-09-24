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
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidCommitMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidNackMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseBatchMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseDigest;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidProposeMessage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ORCHID phase / propose / commit / nack encode/decode.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class PhaseRpcCodec {
	/**
	 * Fixed scalars after length-prefixed nodeId in one phase payload:
	 * phase, omega, lastCommittedSeq, proposeId, digest.
	 */
	private static final int PHASE_SCALAR_BYTES =
			Double.BYTES + Double.BYTES + Long.BYTES + Long.BYTES + Long.BYTES;

	private PhaseRpcCodec() {
	}

	public static byte[] encodePhase(OrchidPhaseMessage msg) {
		final byte[] id = msg.nodeId().getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + id.length + PHASE_SCALAR_BYTES);
		encodePhaseInto(buf, id, msg.phase(), msg.omega(), msg.lastCommittedSeq(), msg.proposeId(), msg.digest());
		return EncodeBuffers.toByteArray(buf);
	}

	/**
	 * Write a single phase into {@code buf} (LE). Caller owns capacity; advances position.
	 */
	public static void encodePhaseInto(ByteBuffer buf, OrchidPhaseMessage msg) {
		Objects.requireNonNull(buf, "buf");
		Objects.requireNonNull(msg, "msg");
		encodePhaseInto(buf, msg.nodeId().getBytes(StandardCharsets.UTF_8),
				msg.phase(), msg.omega(), msg.lastCommittedSeq(), msg.proposeId(), msg.digest());
	}

	/**
	 * Expand logical multi-digest batch into N {@link OrchidPhaseMessage} (shared Kuramoto fields).
	 */
	public static List<OrchidPhaseMessage> expandPhaseBatch(OrchidPhaseBatchMessage msg) {
		Objects.requireNonNull(msg, "msg");
		final List<OrchidPhaseDigest> digests = msg.digests() == null ? List.of() : msg.digests();
		if (digests.isEmpty()) {
			return List.of(new OrchidPhaseMessage(
					msg.nodeId(), msg.phase(), msg.omega(), msg.lastCommittedSeq(), 0L, 0L));
		}
		final List<OrchidPhaseMessage> messages = new ArrayList<>(digests.size());
		for (OrchidPhaseDigest digest : digests) {
			messages.add(new OrchidPhaseMessage(
					msg.nodeId(), msg.phase(), msg.omega(), msg.lastCommittedSeq(),
					digest.proposeId(), digest.digest()));
		}
		return messages;
	}

	/**
	 * Encode logical multi-digest batch as {@code ORCHID_PHASE_BATCH}: count + repeated phase payloads.
	 */
	public static byte[] encodePhaseBatch(OrchidPhaseBatchMessage msg) {
		return encodePhaseMessageBatch(expandPhaseBatch(msg));
	}

	/**
	 * Pack N phase messages: {@code int32 count} + repeated {@link #encodePhase} body layout.
	 * Empty list yields count=0.
	 */
	public static byte[] encodePhaseMessageBatch(List<OrchidPhaseMessage> messages) {
		final List<OrchidPhaseMessage> list = messages == null ? List.of() : messages;
		int payloadBytes = 0;
		final List<byte[]> ids = new ArrayList<>(list.size());
		for (OrchidPhaseMessage msg : list) {
			Objects.requireNonNull(msg, "msg");
			final byte[] id = msg.nodeId().getBytes(StandardCharsets.UTF_8);
			ids.add(id);
			payloadBytes += Integer.BYTES + id.length + PHASE_SCALAR_BYTES;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(Integer.BYTES + payloadBytes);
		buf.putInt(list.size());
		for (int i = 0; i < list.size(); i++) {
			final OrchidPhaseMessage msg = list.get(i);
			encodePhaseInto(buf, ids.get(i), msg.phase(), msg.omega(),
					msg.lastCommittedSeq(), msg.proposeId(), msg.digest());
		}
		return EncodeBuffers.toByteArray(buf);
	}

	/**
	 * Write packed phase messages into {@code buf} (LE). Caller owns capacity; advances position.
	 */
	public static void encodePhaseMessageBatchInto(ByteBuffer buf, List<OrchidPhaseMessage> messages) {
		Objects.requireNonNull(buf, "buf");
		final List<OrchidPhaseMessage> list = messages == null ? List.of() : messages;
		buf.putInt(list.size());
		for (OrchidPhaseMessage msg : list) {
			Objects.requireNonNull(msg, "msg");
			encodePhaseInto(buf, msg);
		}
	}

	/**
	 * Decode {@code ORCHID_PHASE_BATCH} body into individual phase messages (same order as encoded).
	 */
	public static List<OrchidPhaseMessage> decodePhaseMessageBatch(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final int count = buf.getInt();
		if (count < 0) {
			throw new IllegalArgumentException("ORCHID_PHASE_BATCH negative count");
		}
		final List<OrchidPhaseMessage> messages = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			messages.add(decodePhaseFrom(buf));
		}
		return messages;
	}

	public static OrchidPhaseMessage decodePhase(byte[] body) {
		return decodePhaseFrom(EncodeBuffers.wrapLe(body));
	}

	private static OrchidPhaseMessage decodePhaseFrom(ByteBuffer buf) {
		return new OrchidPhaseMessage(EncodeBuffers.getUtf8(buf), buf.getDouble(), buf.getDouble(),
				buf.getLong(), buf.getLong(), buf.getLong());
	}

	private static void encodePhaseInto(ByteBuffer buf, byte[] id, double phase, double omega,
	                                    long lastCommittedSeq, long proposeId, long digest) {
		EncodeBuffers.putLengthPrefixed(buf, id);
		buf.putDouble(phase);
		buf.putDouble(omega);
		buf.putLong(lastCommittedSeq);
		buf.putLong(proposeId);
		buf.putLong(digest);
	}

	public static byte[] encodePropose(OrchidProposeMessage msg) {
		final byte[] id = msg.proposerId().getBytes(StandardCharsets.UTF_8);
		final byte[] op = OpLogCodec.encodeOp(msg.op());
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + id.length + Long.BYTES + Long.BYTES + Long.BYTES + Integer.BYTES + op.length);
		EncodeBuffers.putLengthPrefixed(buf, id);
		buf.putLong(msg.proposeId());
		buf.putLong(msg.digest());
		buf.putLong(msg.prevOpSeq());
		buf.putInt(op.length);
		buf.put(op);
		return EncodeBuffers.toByteArray(buf);
	}

	public static OrchidProposeMessage decodePropose(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final String proposer = EncodeBuffers.getUtf8(buf);
		final long proposeId = buf.getLong();
		final long digest = buf.getLong();
		final long prevOpSeq = buf.getLong();
		final int len = buf.getInt();
		final byte[] opBytes = new byte[len];
		buf.get(opBytes);
		return new OrchidProposeMessage(proposer, proposeId, digest, prevOpSeq, OpLogCodec.decodeOp(opBytes));
	}

	public static byte[] encodeCommit(OrchidCommitMessage msg) {
		final byte[] id = msg.committerId().getBytes(StandardCharsets.UTF_8);
		final byte[] op = OpLogCodec.encodeOp(msg.op());
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + id.length + Long.BYTES + Long.BYTES + Long.BYTES + Long.BYTES
						+ Integer.BYTES + op.length);
		EncodeBuffers.putLengthPrefixed(buf, id);
		buf.putLong(msg.proposeId());
		buf.putLong(msg.digest());
		buf.putLong(msg.prevOpSeq());
		buf.putLong(msg.opSeq());
		buf.putInt(op.length);
		buf.put(op);
		return EncodeBuffers.toByteArray(buf);
	}

	public static OrchidCommitMessage decodeCommit(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final String committer = EncodeBuffers.getUtf8(buf);
		final long proposeId = buf.getLong();
		final long digest = buf.getLong();
		final long prevOpSeq = buf.getLong();
		final long opSeq = buf.getLong();
		final int len = buf.getInt();
		final byte[] opBytes = new byte[len];
		buf.get(opBytes);
		return new OrchidCommitMessage(committer, proposeId, digest, prevOpSeq, opSeq, OpLogCodec.decodeOp(opBytes));
	}

	public static byte[] encodeOrchidNack(OrchidNackMessage msg) {
		final byte[] id = msg.fromNodeId().getBytes(StandardCharsets.UTF_8);
		final byte[] reason = msg.reason() == null ? new byte[0] : msg.reason().getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + id.length + Long.BYTES + Integer.BYTES + reason.length);
		EncodeBuffers.putLengthPrefixed(buf, id);
		buf.putLong(msg.proposeId());
		EncodeBuffers.putLengthPrefixed(buf, reason);
		return EncodeBuffers.toByteArray(buf);
	}

	public static OrchidNackMessage decodeOrchidNack(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		return new OrchidNackMessage(EncodeBuffers.getUtf8(buf), buf.getLong(), EncodeBuffers.getUtf8(buf));
	}
}
