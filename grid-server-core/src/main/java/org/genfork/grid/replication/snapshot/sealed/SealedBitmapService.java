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

import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Lifecycle and path service for sealed per-shard BITMAP indexes ({@code *.sbm}).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedBitmapService implements AutoCloseable {
	private final Path sealedRoot;

	public SealedBitmapService(Path sealedRoot) {
		this.sealedRoot = Objects.requireNonNull(sealedRoot, "sealedRoot");
	}

	public Path indexFile(String domain, int shard, String indexName) {
		final String hex = SealedShardPack.domainHex(domain);
		return sealedRoot.resolve(hex + "_" + shard + "_idx_" + safeName(indexName) + ".sbm");
	}

	/**
	 * Persist bitmap working-set postings for one domain/shard/index (opt-in BITMAP only).
	 */
	public void dumpIndex(String domain, int shard, String indexName, GridBitmapIndex bitmap)
			throws IOException {
		Objects.requireNonNull(bitmap, "bitmap");
		final Path file = indexFile(domain, shard, indexName);
		SealedBitmapWriter.write(file, domain, shard, indexName, bitmap.serialize());
	}

	/**
	 * Hydrate a BITMAP index from sealed file when present; no-op if file missing.
	 *
	 * @return hydrated index, or {@code null} when sealed file is absent
	 */
	public GridBitmapIndex openIfPresent(String domain, int shard, String indexName) throws IOException {
		final Path file = indexFile(domain, shard, indexName);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		final byte[] payload = SealedBitmapWriter.readPayload(file);
		return GridBitmapIndex.deserialize(indexName + "-BITMAP", payload);
	}

	@Override
	public void close() {
		// Stateless path helper — nothing to release.
	}

	private static String safeName(String indexName) {
		Objects.requireNonNull(indexName, "indexName");
		final String safe = indexName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		if (safe.isBlank()) {
			throw new IllegalArgumentException("indexName has no safe filename characters");
		}
		return safe;
	}
}