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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.genfork.grid.mem.index.GridCompositeIndex;

/**
 * Sealed {@code .sbpt} accelerator helpers: incomplete / authoritative file detection.
 * <p>
 * Empty shells ({@code entryCount == 0}) and truncated debris are <em>absent</em>
 * for bind / at-risk — not authoritative fallbacks. Permanent RAM rebuild on LAZY
 * open is intentionally not provided (OOM / working-set risk). Seal dump builds
 * {@code .sbpt} from the merge map — never by mutating live RAM BPTrees under query.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class SealedLazyIndexRebuildUtil {

	private SealedLazyIndexRebuildUtil() {
	}

	/**
	 * True when any single-column or composite sealed BPTree file is missing, truncated,
	 * or reports {@code entryCount == 0}.
	 */
	public static boolean anyAcceleratorIncomplete(
			SealedBPTreeService sealedBPTreeService,
			String domainType,
			int shard,
			GridCompositeIndex composite
	) {
		Objects.requireNonNull(sealedBPTreeService, "sealedBPTreeService");
		Objects.requireNonNull(domainType, "domainType");
		Objects.requireNonNull(composite, "composite");
		final AtomicBoolean incomplete = new AtomicBoolean(false);
		composite.forEachSingleColumnBPTree((property, tree) -> {
			if (incomplete.get()) {
				return;
			}
			final Path idxFile = sealedBPTreeService.indexFile(domainType, shard, property);
			if (isAcceleratorIncomplete(idxFile)) {
				incomplete.set(true);
			}
		});
		if (incomplete.get()) {
			return true;
		}
		composite.forEachCompositeBPTree((columns, tree) -> {
			if (incomplete.get()) {
				return;
			}
			final String sealedName = SealedCompositeIndexNames.of(columns);
			final Path idxFile = sealedBPTreeService.indexFile(domainType, shard, sealedName);
			if (isAcceleratorIncomplete(idxFile)) {
				incomplete.set(true);
			}
		});
		return incomplete.get();
	}

	/**
	 * Missing / truncated debris / empty tree shell → incomplete accelerator (treat as absent).
	 */
	public static boolean isAcceleratorIncomplete(Path idxFile) {
		return !isAuthoritativeSealedIndexFile(idxFile);
	}

	/**
	 * Authoritative accelerator: regular file ≥ {@link SealedBPTreeReader#MIN_FILE_BYTES}
	 * with {@code entryCount > 0}. Empty shells and truncated peer debris = absent.
	 */
	public static boolean isAuthoritativeSealedIndexFile(Path idxFile) {
		if (!isParseableSealedIndexFile(idxFile)) {
			return false;
		}
		try (SealedBPTreeReader peek = SealedBPTreeReader.open(idxFile)) {
			return peek.entryCount() > 0L;
		} catch (IOException ignored) {
			return false;
		}
	}

	/**
	 * Regular file at least {@link SealedBPTreeReader#MIN_FILE_BYTES}; truncated peer debris = absent.
	 * Does <em>not</em> require non-zero {@code entryCount} — use
	 * {@link #isAuthoritativeSealedIndexFile} for bind / at-risk.
	 */
	public static boolean isParseableSealedIndexFile(Path idxFile) {
		if (idxFile == null) {
			return false;
		}
		try {
			return Files.isRegularFile(idxFile) && Files.size(idxFile) >= SealedBPTreeReader.MIN_FILE_BYTES;
		} catch (IOException ignored) {
			return false;
		}
	}
}
