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
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdateCommitDec;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdateLockAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdateLockRelease;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdateLockReq;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdatePrepareAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ForUpdatePrepareReq;
import org.genfork.grid.replication.transport.ReplicationMessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * FOR UPDATE lock / prepare / commit-dec encode/decode (distributed TX scaffold).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class TxRpcCodec {
	private static final byte LOCK_FLAG_SKIP = 1;
	private static final byte LOCK_FLAG_GRANTED = 1;
	private static final byte PREPARE_FLAG_OK = 1;
	private static final byte COMMIT_DEC_FLAG_COMMIT = 1;

	private TxRpcCodec() {
	}

	/**
	 * {@link ReplicationMessageType#FOR_UPDATE_LOCK_REQ}: txId, skipLocked, table, keyBytes.
	 */
	public static byte[] encodeForUpdateLockReq(
			long txId,
			boolean skipLocked,
			String table,
			byte[] key
	) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		final byte[] tableBytes = table.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Long.BYTES + Byte.BYTES + Integer.BYTES + tableBytes.length
						+ Integer.BYTES + key.length);
		buf.putLong(txId);
		buf.put(skipLocked ? LOCK_FLAG_SKIP : (byte) 0);
		EncodeBuffers.putLengthPrefixed(buf, tableBytes);
		EncodeBuffers.putLengthPrefixed(buf, key);
		return EncodeBuffers.toByteArray(buf);
	}

	public static ForUpdateLockReq decodeForUpdateLockReq(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final long txId = buf.getLong();
		final boolean skipLocked = buf.get() == LOCK_FLAG_SKIP;
		final String table = EncodeBuffers.getUtf8(buf);
		final int keyLen = buf.getInt();
		if (keyLen < 0 || keyLen > buf.remaining()) {
			throw new IllegalArgumentException("invalid FOR UPDATE lock key length: " + keyLen);
		}
		final byte[] key = new byte[keyLen];
		buf.get(key);
		return new ForUpdateLockReq(txId, skipLocked, table, key);
	}

	public static byte[] encodeForUpdateLockAck(long txId, boolean granted, String fromNodeId) {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Long.BYTES + Byte.BYTES + Integer.BYTES + from.length);
		buf.putLong(txId);
		buf.put(granted ? LOCK_FLAG_GRANTED : (byte) 0);
		EncodeBuffers.putLengthPrefixed(buf, from);
		return EncodeBuffers.toByteArray(buf);
	}

	public static ForUpdateLockAck decodeForUpdateLockAck(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final long txId = buf.getLong();
		final boolean granted = buf.get() == LOCK_FLAG_GRANTED;
		final String fromNodeId = EncodeBuffers.getUtf8(buf);
		return new ForUpdateLockAck(txId, granted, fromNodeId);
	}

	public static byte[] encodeForUpdateLockRelease(long txId, String table, byte[] key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		final byte[] tableBytes = table.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Long.BYTES + Integer.BYTES + tableBytes.length + Integer.BYTES + key.length);
		buf.putLong(txId);
		EncodeBuffers.putLengthPrefixed(buf, tableBytes);
		EncodeBuffers.putLengthPrefixed(buf, key);
		return EncodeBuffers.toByteArray(buf);
	}

	public static ForUpdateLockRelease decodeForUpdateLockRelease(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final long txId = buf.getLong();
		final String table = EncodeBuffers.getUtf8(buf);
		final int keyLen = buf.getInt();
		if (keyLen < 0 || keyLen > buf.remaining()) {
			throw new IllegalArgumentException("invalid FOR UPDATE release key length: " + keyLen);
		}
		final byte[] key = new byte[keyLen];
		buf.get(key);
		return new ForUpdateLockRelease(txId, table, key);
	}

	public static byte[] encodeForUpdatePrepareReq(long txId, String fromNodeId) {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(Long.BYTES + Integer.BYTES + from.length);
		buf.putLong(txId);
		EncodeBuffers.putLengthPrefixed(buf, from);
		return EncodeBuffers.toByteArray(buf);
	}

	public static ForUpdatePrepareReq decodeForUpdatePrepareReq(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final long txId = buf.getLong();
		final String fromNodeId = EncodeBuffers.getUtf8(buf);
		return new ForUpdatePrepareReq(txId, fromNodeId);
	}

	public static byte[] encodeForUpdatePrepareAck(long txId, boolean prepared, String fromNodeId) {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Long.BYTES + Byte.BYTES + Integer.BYTES + from.length);
		buf.putLong(txId);
		buf.put(prepared ? PREPARE_FLAG_OK : (byte) 0);
		EncodeBuffers.putLengthPrefixed(buf, from);
		return EncodeBuffers.toByteArray(buf);
	}

	public static ForUpdatePrepareAck decodeForUpdatePrepareAck(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final long txId = buf.getLong();
		final boolean prepared = buf.get() == PREPARE_FLAG_OK;
		final String fromNodeId = EncodeBuffers.getUtf8(buf);
		return new ForUpdatePrepareAck(txId, prepared, fromNodeId);
	}

	public static byte[] encodeForUpdateCommitDec(long txId, boolean commit, String fromNodeId) {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Long.BYTES + Byte.BYTES + Integer.BYTES + from.length);
		buf.putLong(txId);
		buf.put(commit ? COMMIT_DEC_FLAG_COMMIT : (byte) 0);
		EncodeBuffers.putLengthPrefixed(buf, from);
		return EncodeBuffers.toByteArray(buf);
	}

	public static ForUpdateCommitDec decodeForUpdateCommitDec(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final long txId = buf.getLong();
		final boolean commit = buf.get() == COMMIT_DEC_FLAG_COMMIT;
		final String fromNodeId = EncodeBuffers.getUtf8(buf);
		return new ForUpdateCommitDec(txId, commit, fromNodeId);
	}
}
