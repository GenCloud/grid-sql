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
package org.genfork.grid.sql.tx;

import org.genfork.grid.nio.EncodeBuffers;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Wire encode/decode for Dist FOR UPDATE prepare requests that carry a key-set.
 * <p>
 * Layout: {@code txId | fromNodeId | keyCount | (table | key)* }. Keys stay {@code byte[]}
 * (no mid-pipeline Object decode).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class ForUpdatePrepareWireUtil {

	private ForUpdatePrepareWireUtil() {
	}

	/**
	 * One prepare key (table + serialized PK bytes).
	 *
	 * @author: GenCloud
	 * @date: 2026/10
	 * @since: 1.0
	 */
	public record TableKey(String table, byte[] key) {
		public TableKey {
			Objects.requireNonNull(table, "table");
			Objects.requireNonNull(key, "key");
		}
	}

	public static byte[] encodeReq(long txId, String fromNodeId, List<TableKey> keys) {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		Objects.requireNonNull(keys, "keys");
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		int body = Long.BYTES + Integer.BYTES + from.length + Integer.BYTES;
		final ArrayList<byte[]> tableBytes = new ArrayList<>(keys.size());
		for (TableKey tk : keys) {
			final byte[] tb = tk.table().getBytes(StandardCharsets.UTF_8);
			tableBytes.add(tb);
			body += Integer.BYTES + tb.length + Integer.BYTES + tk.key().length;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(body);
		buf.putLong(txId);
		EncodeBuffers.putLengthPrefixed(buf, from);
		buf.putInt(keys.size());
		for (int i = 0; i < keys.size(); i++) {
			EncodeBuffers.putLengthPrefixed(buf, tableBytes.get(i));
			EncodeBuffers.putLengthPrefixed(buf, keys.get(i).key());
		}
		return EncodeBuffers.toByteArray(buf);
	}

	public static DecodedPrepareReq decodeReq(byte[] body) {
		Objects.requireNonNull(body, "body");
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final long txId = buf.getLong();
		final String fromNodeId = EncodeBuffers.getUtf8(buf);
		final int count = buf.getInt();
		if (count < 0 || count > body.length) {
			throw new IllegalArgumentException("invalid FOR UPDATE prepare key count: " + count);
		}
		final ArrayList<TableKey> keys = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final String table = EncodeBuffers.getUtf8(buf);
			final int keyLen = buf.getInt();
			if (keyLen < 0 || keyLen > buf.remaining()) {
				throw new IllegalArgumentException("invalid FOR UPDATE prepare key length: " + keyLen);
			}
			final byte[] key = new byte[keyLen];
			buf.get(key);
			keys.add(new TableKey(table, key));
		}
		return new DecodedPrepareReq(txId, fromNodeId, List.copyOf(keys));
	}

	/**
	 * Decoded prepare request.
	 *
	 * @author: GenCloud
	 * @date: 2026/10
	 * @since: 1.0
	 */
	public record DecodedPrepareReq(long txId, String fromNodeId, List<TableKey> keys) {
		public DecodedPrepareReq {
			Objects.requireNonNull(fromNodeId, "fromNodeId");
			Objects.requireNonNull(keys, "keys");
		}
	}
}