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
package org.genfork.grid.replication.repair;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.durable.ChannelDurableIo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Durable sidecar for {@link VersionLocusMap}: one file per domain#shard under locus/.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class FileDurableLocusStore implements AutoCloseable {
	private final Path root;

	public FileDurableLocusStore(Path root) {
		this.root = root;
		try {
			GridFs.createDirs(root);
		} catch (IOException e) {
			throw new IllegalStateException("locus dir", e);
		}
	}

	public void persist(VersionLocusMap map, String domainType, int shard) {
		final Map<Long, VersionLocus> snap = map.snapshot(domainType, shard);
		final Path file = root.resolve(safe(domainType) + "_" + shard + ".locus");
		try {
			int bytes = 4;
			for (VersionLocus locus : snap.values()) {
				final byte[] domain = locus.domainType().getBytes(StandardCharsets.UTF_8);
				bytes += 4 + domain.length + 4 + 8 + 8 + 8 + 8;
			}
			final ByteBuffer all = ByteBuffer.allocate(bytes);
			all.putInt(snap.size());
			for (VersionLocus locus : snap.values()) {
				final byte[] domain = locus.domainType().getBytes(StandardCharsets.UTF_8);
				all.putInt(domain.length);
				all.put(domain);
				all.putInt(locus.shard());
				all.putLong(locus.keyHash());
				all.putLong(locus.opSeq());
				all.putLong(locus.schemaEpoch());
				all.putLong(locus.contentChecksum());
			}
			all.flip();
			ChannelDurableIo.rewriteFile(file, all, false);
		} catch (IOException e) {
			throw new IllegalStateException("locus persist failed", e);
		}
	}

	public void loadInto(VersionLocusMap map) {
		try {
			if (!GridFs.isDirectory(root)) {
				return;
			}
			try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(root, "*.locus")) {
				for (Path file : stream) {
					loadFile(map, file);
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("locus load failed", e);
		}
	}

	private void loadFile(VersionLocusMap map, Path file) {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			final ByteBuffer hdr = ByteBuffer.allocate(4);
			if (ch.read(hdr) < 4) {
				return;
			}
			hdr.flip();
			final int n = hdr.getInt();
			for (int i = 0; i < n; i++) {
				final ByteBuffer lenBuf = ByteBuffer.allocate(4);
				if (ch.read(lenBuf) < 4) {
					return;
				}
				lenBuf.flip();
				final int dlen = lenBuf.getInt();
				final ByteBuffer rest = ByteBuffer.allocate(dlen + 4 + 8 + 8 + 8 + 8);
				if (ch.read(rest) < rest.capacity()) {
					return;
				}
				rest.flip();
				final byte[] domainBytes = new byte[dlen];
				rest.get(domainBytes);
				final String domain = new String(domainBytes, StandardCharsets.UTF_8);
				final int shard = rest.getInt();
				final long keyHash = rest.getLong();
				final long opSeq = rest.getLong();
				final long epoch = rest.getLong();
				final long checksum = rest.getLong();
				map.put(new VersionLocus(domain, shard, keyHash, opSeq, epoch, checksum));
			}
		} catch (IOException e) {
			throw new IllegalStateException("locus read " + file, e);
		}
	}

	private static String safe(String domainType) {
		return Integer.toHexString(domainType.hashCode());
	}

	@Override
	public void close() {
		// no-op
	}
}
