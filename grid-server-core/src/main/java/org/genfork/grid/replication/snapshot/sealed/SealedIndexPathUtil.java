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

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Unified sealed secondary-index filename and path rules for {@code .sbpt} and {@code .sbm}.
 * <p>
 * Keeps composite logical names ({@code col1+col2}) distinct via {@code +} in the safe segment.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class SealedIndexPathUtil {
	private SealedIndexPathUtil() {
	}

	/**
	 * Lowercase-safe filename segment for {@code indexName} (retains {@code +} for composites).
	 */
	public static String safeIndexFileName(String indexName) {
		Objects.requireNonNull(indexName, "indexName");
		// Allow '+' so composite sealed names (col1+col2) stay distinct from col1_col2.
		final String safe = indexName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._+-]", "_");
		if (safe.isBlank()) {
			throw new IllegalArgumentException("indexName has no safe filename characters");
		}
		return safe;
	}

	/**
	 * Path under {@code sealedRoot} for one shard index file.
	 *
	 * @param extension includes leading dot (e.g. {@code ".sbpt"}, {@code ".sbm"})
	 */
	public static Path indexFile(
			Path sealedRoot,
			String domain,
			int shard,
			String indexName,
			String extension) {
		Objects.requireNonNull(sealedRoot, "sealedRoot");
		Objects.requireNonNull(domain, "domain");
		Objects.requireNonNull(extension, "extension");
		final String hex = SealedShardPack.domainHex(domain);
		final String safe = safeIndexFileName(indexName);
		return sealedRoot.resolve(hex + "_" + shard + "_idx_" + safe + extension);
	}
}
