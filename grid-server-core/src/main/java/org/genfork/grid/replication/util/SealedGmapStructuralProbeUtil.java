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
package org.genfork.grid.replication.util;

import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil.Probe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Local GMAP structural integrity probe (magic-OK undersize → {@link Probe#TRUNCATED}).
 * <p>
 * Mirrors directory-bound checks of sealed open without mmap/CRC — forever detect
 * truncated node files without requiring peer root fingerprint equality.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SealedGmapStructuralProbeUtil {

	/** Fixed LE header before domain bytes (see sealed writer/reader). */
	public static final int FIXED_HEADER_BYTES = 32;
	/** VERSION=2 trailing node id after domain. */
	public static final int NODE_ID_BYTES = 4;
	/** Bucket directory slot size. */
	public static final int BUCKET_DIR_BYTES = 8;
	/** Max domain name bytes accepted by sealed open. */
	public static final int MAX_DOMAIN_NAME_BYTES = 1 << 20;

	private SealedGmapStructuralProbeUtil() {
	}

	/**
	 * Probe one {@code .gmap}: missing / magic / truncated directory vs file size.
	 */
	public static Probe probe(Path gmapPath) {
		if (gmapPath == null || !Files.isRegularFile(gmapPath)) {
			return Probe.MISSING;
		}
		try {
			final long fileSize = Files.size(gmapPath);
			if (fileSize < FIXED_HEADER_BYTES) {
				return Probe.TRUNCATED;
			}
			try (FileChannel channel = FileChannel.open(gmapPath, StandardOpenOption.READ)) {
				final ByteBuffer fixed = ByteBuffer.allocate(FIXED_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
				if (channel.read(fixed, 0L) < FIXED_HEADER_BYTES) {
					return Probe.TRUNCATED;
				}
				fixed.flip();
				final int magic = fixed.getInt();
				if (magic != SealedGridMapWriter.MAGIC) {
					return Probe.BAD_MAGIC;
				}
				final int version = fixed.getInt();
				final int nodeCount = fixed.getInt();
				final int capacity = fixed.getInt();
				fixed.getInt(); // shard
				fixed.getLong(); // watermark
				final int domainLength = fixed.getInt();
				if (domainLength < 0 || domainLength > MAX_DOMAIN_NAME_BYTES
						|| FIXED_HEADER_BYTES + (long) domainLength > fileSize) {
					return Probe.TRUNCATED;
				}
				if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
					return Probe.BAD_MAGIC;
				}
				final long headerBytes;
				if (version == SealedGridMapWriter.VERSION_NODE) {
					if (nodeCount != 1) {
						return Probe.BAD_MAGIC;
					}
					if (FIXED_HEADER_BYTES + (long) domainLength + NODE_ID_BYTES > fileSize) {
						return Probe.TRUNCATED;
					}
					headerBytes = FIXED_HEADER_BYTES + (long) domainLength + NODE_ID_BYTES;
				} else {
					headerBytes = FIXED_HEADER_BYTES + (long) domainLength;
				}
				final long bucketBytes = Math.multiplyExact(Math.multiplyExact((long) nodeCount, capacity), BUCKET_DIR_BYTES);
				final long minimumDirectory = Math.addExact(headerBytes, bucketBytes);
				if (minimumDirectory > SealedGridMapWriter.MAX_FILE_BYTES
						|| minimumDirectory > Integer.MAX_VALUE
						|| minimumDirectory > fileSize) {
					return Probe.TRUNCATED;
				}
				final ByteBuffer buckets = ByteBuffer.allocate(Math.toIntExact(bucketBytes))
						.order(ByteOrder.LITTLE_ENDIAN);
				if (channel.read(buckets, headerBytes) < bucketBytes) {
					return Probe.TRUNCATED;
				}
				buckets.flip();
				int entryCount = 0;
				final long slots = (long) nodeCount * capacity;
				for (long slot = 0; slot < slots; slot++) {
					final int start = buckets.getInt();
					final int count = buckets.getInt();
					if (start < 0 || count < 0) {
						return Probe.BAD_MAGIC;
					}
					entryCount = Math.max(entryCount, Math.addExact(start, count));
				}
				final long directoryBytes = Math.addExact(minimumDirectory,
						Math.multiplyExact((long) entryCount, SealedGridMapWriter.ENTRY_DIR_BYTES));
				if (directoryBytes > SealedGridMapWriter.MAX_FILE_BYTES
						|| directoryBytes > Integer.MAX_VALUE
						|| directoryBytes > fileSize) {
					return Probe.TRUNCATED;
				}
				final long payloadEnd = fileSize - Integer.BYTES;
				if (payloadEnd < directoryBytes) {
					return Probe.TRUNCATED;
				}
				return Probe.OK;
			}
		} catch (IOException | ArithmeticException ignored) {
			return Probe.IO_ERROR;
		}
	}
}
