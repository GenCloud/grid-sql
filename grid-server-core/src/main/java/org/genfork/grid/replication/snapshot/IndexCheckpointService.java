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
package org.genfork.grid.replication.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.zip.CRC32;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.utils.ArrayVectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable index checkpoint under {@code index-ckpt/}.
 * <p>
 * {@code .meta} (+ optional {@code .idx} entry-count) and {@code .bytes} are a
 * <strong>KEYS watermark catalog</strong> (CRC/seq eligibility for rebuild / hydrate),
 * not a full dump of every RAM BPTree or bitmap pointer. Sealed {@code .sbpt}/{@code .sbm}
 * remain the durable secondary-index source of truth; cold start still rebuilds or
 * rehydrates from sealed / map when CRC/seq miss.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public class IndexCheckpointService {
	private static final Logger log = LoggerFactory.getLogger(IndexCheckpointService.class);

	private static final byte[] IDX_MAGIC = "IDX1".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] BYTES_MAGIC = "IDXB".getBytes(StandardCharsets.US_ASCII);

	private final Path root;

	public IndexCheckpointService(Path root) {
		this.root = root;
		if (root != null) {
			try {
				GridFs.createDirs(root);
			} catch (IOException e) {
				throw new IllegalStateException("index-ckpt dir", e);
			}
		}
	}

	public void markRebuilt(String domainType, long throughSeq) {
		markRebuilt(domainType, throughSeq, -1L);
	}

	/**
	 * @param entryCount optional index entry count sidecar ({@code >= 0} writes {@code .idx}); {@code < 0} skips
	 */
	public void markRebuilt(String domainType, long throughSeq, long entryCount) {
		if (root == null || domainType == null) {
			return;
		}
		try {
			final Path file = root.resolve(safe(domainType) + ".meta");
			final String body = domainType + "\n" + throughSeq + "\n" + Instant.now();
			GridFs.writeAtomic(file, body, StandardCharsets.UTF_8);
			if (entryCount >= 0) {
				writeIdxPlaceholder(domainType, throughSeq, entryCount);
			}
		} catch (IOException ex) {
			log.warn("Failed to write index checkpoint for {}: {}", domainType, ex.toString());
		}
	}

	public void writeIdxPlaceholder(String domainType, long throughSeq, long entryCount) {
		if (root == null || domainType == null) {
			return;
		}
		try {
			final Path idx = root.resolve(safe(domainType) + ".idx");
			final String body = new String(IDX_MAGIC, StandardCharsets.US_ASCII)
					+ "\n" + domainType
					+ "\n" + throughSeq
					+ "\n" + entryCount
					+ "\n" + Instant.now();
			GridFs.writeAtomic(idx, body, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			log.warn("Failed to write index .idx for {}: {}", domainType, ex.toString());
		}
	}

	/** Persist KEYS catalog bytes (watermark payload) with CRC32 footer — not a full RAM index dump. */
	public void writeIndexBytes(String domainType, long throughSeq, byte[] payload) {
		if (root == null || domainType == null || payload == null) {
			return;
		}
		try {
			final Path file = root.resolve(safe(domainType) + ".bytes");
			final CRC32 crc = new CRC32();
			ArrayVectors.updateCrc32(crc, payload);
			final ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 4 + payload.length + 4);
			buf.put(BYTES_MAGIC);
			buf.putLong(throughSeq);
			buf.putInt(payload.length);
			buf.put(payload);
			buf.putInt((int) crc.getValue());
			GridFs.writeAtomic(file, buf.array());
		} catch (IOException ex) {
			log.warn("Failed to write index .bytes for {}: {}", domainType, ex.toString());
		}
	}

	/**
	 * @return payload if magic/CRC/seq ok and {@code expectedThroughSeq} matches (or expected &lt; 0 to skip seq check);
	 * null if absent/corrupt/mismatch
	 */
	public byte[] loadIndexBytes(String domainType, long expectedThroughSeq) {
		if (root == null || domainType == null) {
			return null;
		}
		final Path file = root.resolve(safe(domainType) + ".bytes");
		if (!GridFs.isRegularFile(file)) {
			return null;
		}
		try {
			final byte[] all = GridFs.readAll(file);
			if (all.length < 4 + 8 + 4 + 4) {
				return null;
			}
			final ByteBuffer buf = ByteBuffer.wrap(all);
			final byte[] magic = new byte[4];
			buf.get(magic);
			if (!Arrays.equals(magic, BYTES_MAGIC)) {
				return null;
			}
			final long seq = buf.getLong();
			if (expectedThroughSeq >= 0 && seq != expectedThroughSeq) {
				return null;
			}
			final int len = buf.getInt();
			if (len < 0 || buf.remaining() < len + 4) {
				return null;
			}
			final byte[] payload = new byte[len];
			buf.get(payload);
			final int expectCrc = buf.getInt();
			final CRC32 crc = new CRC32();
			ArrayVectors.updateCrc32(crc, payload);
			if ((int) crc.getValue() != expectCrc) {
				return null;
			}
			return payload;
		} catch (Exception ex) {
			log.warn("Failed to read index .bytes for {}: {}", domainType, ex.toString());
			return null;
		}
	}

	public long loadThroughSeq(String domainType) {
		if (root == null || domainType == null) {
			return 0L;
		}
		final Path file = root.resolve(safe(domainType) + ".meta");
		if (!GridFs.isRegularFile(file)) {
			return 0L;
		}
		try {
			final String[] lines = GridFs.readString(file, StandardCharsets.UTF_8).split("\n");
			if (lines.length >= 2) {
				return Long.parseLong(lines[1].trim());
			}
		} catch (Exception ex) {
			log.warn("Failed to read index checkpoint for {}: {}", domainType, ex.toString());
		}
		return 0L;
	}

	/**
	 * @return entry count from {@code .idx} sidecar, or {@code -1} if absent/invalid
	 */
	public long loadIdxEntryCount(String domainType) {
		if (root == null || domainType == null) {
			return -1L;
		}
		final Path idx = root.resolve(safe(domainType) + ".idx");
		if (!GridFs.isRegularFile(idx)) {
			return -1L;
		}
		try {
			final String[] lines = GridFs.readString(idx, StandardCharsets.UTF_8).split("\n");
			if (lines.length >= 4 && lines[0].startsWith("IDX1")) {
				return Long.parseLong(lines[3].trim());
			}
		} catch (Exception ex) {
			log.warn("Failed to read index .idx for {}: {}", domainType, ex.toString());
		}
		return -1L;
	}

	/**
	 * DROP TABLE: remove KEYS catalog sidecars so recreate does not soft-match stale seq.
	 */
	public void deleteDomain(String domainType) {
		if (root == null || domainType == null) {
			return;
		}
		final String base = safe(domainType);
		GridFs.deleteQuietly(root.resolve(base + ".meta"));
		GridFs.deleteQuietly(root.resolve(base + ".idx"));
		GridFs.deleteQuietly(root.resolve(base + ".bytes"));
	}

	private static String safe(String domainType) {
		return Integer.toHexString(domainType.hashCode());
	}
}

