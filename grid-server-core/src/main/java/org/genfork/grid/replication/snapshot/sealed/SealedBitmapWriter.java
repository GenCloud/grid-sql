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
package org.genfork.grid.replication.snapshot.sealed;

import org.genfork.grid.fs.GridFs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Durable sealed BITMAP index file writer/reader ({@code *.sbm}) under sealed/dataDir.
 * <p>
 * Reuses {@link ChannelDurableIo} — same rewrite helper as {@link SealedShardPack}.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedBitmapWriter {
	public static final int MAGIC = 0x53424D31;
	public static final int VERSION = 1;

	private SealedBitmapWriter() {
	}

	/**
	 * Write sealed bitmap file: header (domain/shard/name) + opaque {@code GridBitmapIndex} payload + CRC32.
	 */
	public static void write(Path file, String domain, int shard, String indexName, byte[] payload)
			throws IOException {
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(domain, "domain");
		Objects.requireNonNull(indexName, "indexName");
		final byte[] body = payload == null ? new byte[0] : payload;
		final byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
		final byte[] indexBytes = indexName.getBytes(StandardCharsets.UTF_8);
		if (domainBytes.length > 0xffff || indexBytes.length > 0xffff) {
			throw new IllegalArgumentException("sealed bitmap metadata exceeds unsigned-short limit");
		}
		final int headerBytes = Integer.BYTES * 3 + Short.BYTES * 2 + domainBytes.length + indexBytes.length
				+ Integer.BYTES;
		final ByteBuffer buffer = ByteBuffer.allocate(headerBytes + body.length + Integer.BYTES)
				.order(ByteOrder.BIG_ENDIAN);
		buffer.putInt(MAGIC).putInt(VERSION).putInt(shard);
		buffer.putShort((short) domainBytes.length).put(domainBytes);
		buffer.putShort((short) indexBytes.length).put(indexBytes);
		buffer.putInt(body.length).put(body);
		final CRC32 crc = new CRC32();
		crc.update(buffer.array(), 0, buffer.position());
		buffer.putInt((int) crc.getValue());

		GridFs.writeAtomic(file, buffer.array());
	}

	/**
	 * Read and validate sealed bitmap payload (CRC + magic/version).
	 */
	public static byte[] readPayload(Path file) throws IOException {
		Objects.requireNonNull(file, "file");
		final byte[] all = GridFs.readAll(file);
		if (all.length < Integer.BYTES * 5 + Short.BYTES * 2) {
			throw new IOException("sealed bitmap too short: " + all.length);
		}
		final ByteBuffer buffer = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
		final int magic = buffer.getInt();
		if (magic != MAGIC) {
			throw new IOException("bad sealed bitmap magic: 0x" + Integer.toHexString(magic));
		}
		final int version = buffer.getInt();
		if (version != VERSION) {
			throw new IOException("unsupported sealed bitmap version: " + version);
		}
		buffer.getInt(); // shard
		final int domainLen = Short.toUnsignedInt(buffer.getShort());
		if (domainLen > buffer.remaining()) {
			throw new IOException("truncated sealed bitmap domain");
		}
		buffer.position(buffer.position() + domainLen);
		final int indexLen = Short.toUnsignedInt(buffer.getShort());
		if (indexLen > buffer.remaining()) {
			throw new IOException("truncated sealed bitmap index name");
		}
		buffer.position(buffer.position() + indexLen);
		if (buffer.remaining() < Integer.BYTES) {
			throw new IOException("truncated sealed bitmap payload length");
		}
		final int payloadLen = buffer.getInt();
		if (payloadLen < 0 || payloadLen > buffer.remaining() - Integer.BYTES) {
			throw new IOException("invalid sealed bitmap payload length: " + payloadLen);
		}
		final byte[] payload = new byte[payloadLen];
		buffer.get(payload);
		final int expectedCrc = buffer.getInt();
		final CRC32 crc = new CRC32();
		crc.update(all, 0, all.length - Integer.BYTES);
		if ((int) crc.getValue() != expectedCrc) {
			throw new IOException("sealed bitmap CRC mismatch");
		}
		if (buffer.hasRemaining()) {
			throw new IOException("trailing bytes in sealed bitmap: " + buffer.remaining());
		}
		return payload;
	}
}