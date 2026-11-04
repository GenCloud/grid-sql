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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compacted catalog {@code ddl.sql} snapshot ship for peer object-set reconcile.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class CatalogDdlSnapshotRpcCodec {
	private CatalogDdlSnapshotRpcCodec() {
	}

	/**
	 * Snapshot payload: fromNodeId + fingerprint + baseEpoch + N UTF-8 lines.
	 */
	public record CatalogDdlSnapshot(
			String fromNodeId,
			long catalogFingerprint,
			long baseEpochExclusive,
			List<String> compactLines
	) {
		public CatalogDdlSnapshot {
			Objects.requireNonNull(fromNodeId, "fromNodeId");
			compactLines = compactLines == null ? List.of() : List.copyOf(compactLines);
		}
	}

	public static byte[] encode(CatalogDdlSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		final byte[] from = snapshot.fromNodeId().getBytes(StandardCharsets.UTF_8);
		final List<byte[]> lineBytes = new ArrayList<>(snapshot.compactLines().size());
		int linesBytes = 0;
		for (String line : snapshot.compactLines()) {
			final byte[] b = line == null ? new byte[0] : line.getBytes(StandardCharsets.UTF_8);
			lineBytes.add(b);
			linesBytes += Integer.BYTES + b.length;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + from.length
						+ Long.BYTES
						+ Long.BYTES
						+ Integer.BYTES
						+ linesBytes);
		EncodeBuffers.putLengthPrefixed(buf, from);
		buf.putLong(snapshot.catalogFingerprint());
		buf.putLong(snapshot.baseEpochExclusive());
		buf.putInt(lineBytes.size());
		for (byte[] line : lineBytes) {
			EncodeBuffers.putLengthPrefixed(buf, line);
		}
		return EncodeBuffers.toByteArray(buf);
	}

	public static CatalogDdlSnapshot decode(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final String fromNodeId = EncodeBuffers.getUtf8(buf);
		final long fingerprint = buf.getLong();
		final long baseEpoch = buf.getLong();
		final int count = buf.getInt();
		if (count < 0) {
			throw new IllegalArgumentException("negative catalog ddl snapshot line count");
		}
		final ArrayList<String> lines = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			lines.add(EncodeBuffers.getUtf8(buf));
		}
		return new CatalogDdlSnapshot(fromNodeId, fingerprint, baseEpoch, lines);
	}
}
