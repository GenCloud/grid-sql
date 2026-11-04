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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Best-effort delete of sealed {@code .sbpt}/{@code .sbm} for one secondary index.
 * <p>
 * DROP INDEX must remove both catalog-named and composite-column sealed names so LAZY
 * hydrate cannot resurrect a STRICT tree after schema/meta no longer list the index.
 * Callers must {@link SealedIndexLifecycleUtil#detachQuerySealedIndexes} before
 * {@link #purge} so query-path fallbacks do not retain closed readers.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class SealedIndexPurgeUtil {
	private static final char NAME_SEP = '_';

	private SealedIndexPurgeUtil() {
	}

	/**
	 * Unmap readers and delete sealed index files for {@code catalogIndexName} and column-derived names.
	 *
	 * @return number of delete attempts (file paths considered)
	 */
	public static int purge(
			Path sealedRoot,
			String domainType,
			String catalogIndexName,
			List<String> columns,
			SealedBPTreeService bptreeService,
			SealedBitmapService bitmapService,
			Set<Integer> knownShards
	) {
		Objects.requireNonNull(sealedRoot, "sealedRoot");
		Objects.requireNonNull(domainType, "domainType");
		if (!GridFs.isDirectory(sealedRoot)) {
			return 0;
		}
		final Set<String> logicalNames = logicalIndexNames(catalogIndexName, columns);
		if (logicalNames.isEmpty()) {
			return 0;
		}
		final Set<Integer> shards = resolveShards(sealedRoot, domainType, knownShards);
		int attempts = 0;
		for (Integer shardBoxed : shards) {
			if (shardBoxed == null) {
				continue;
			}
			final int shard = shardBoxed.intValue();
			for (String logicalName : logicalNames) {
				if (bptreeService != null) {
					bptreeService.deleteIndex(domainType, shard, logicalName);
					attempts++;
				} else {
					GridFs.deleteQuietly(
							SealedIndexPathUtil.indexFile(sealedRoot, domainType, shard, logicalName, ".sbpt"));
					attempts++;
				}
				if (bitmapService != null) {
					bitmapService.deleteIndex(domainType, shard, logicalName);
					attempts++;
				} else {
					GridFs.deleteQuietly(
							SealedIndexPathUtil.indexFile(sealedRoot, domainType, shard, logicalName, ".sbm"));
					attempts++;
				}
			}
		}
		return attempts;
	}

	static Set<String> logicalIndexNames(String catalogIndexName, List<String> columns) {
		final Set<String> names = new LinkedHashSet<>();
		if (catalogIndexName != null && !catalogIndexName.isBlank()) {
			names.add(catalogIndexName);
		}
		if (columns == null || columns.isEmpty()) {
			return names;
		}
		if (columns.size() == 1) {
			final String column = columns.getFirst();
			if (column != null && !column.isBlank()) {
				names.add(column);
			}
			return names;
		}
		names.add(SealedCompositeIndexNames.of(columns));
		return names;
	}

	static Set<Integer> resolveShards(Path sealedRoot, String domainType, Set<Integer> knownShards) {
		final Set<Integer> shards = new LinkedHashSet<>();
		if (knownShards != null) {
			shards.addAll(knownShards);
		}
		shards.addAll(discoverShards(sealedRoot, domainType));
		return shards;
	}

	static Set<Integer> discoverShards(Path sealedRoot, String domainType) {
		final Set<Integer> shards = new LinkedHashSet<>();
		final String hex = SealedShardPack.domainHex(domainType);
		final String prefix = hex + NAME_SEP;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(sealedRoot, prefix + "*")) {
			for (Path file : stream) {
				final Integer shard = parseShard(file.getFileName().toString(), hex);
				if (shard != null) {
					shards.add(shard);
				}
			}
		} catch (IOException ignored) {
			// best-effort; callers still try knownShards
		}
		return shards;
	}

	/**
	 * Parse shard id from {@code {hex}_{shard}_...} filename.
	 */
	static Integer parseShard(String fileName, String domainHex) {
		if (fileName == null || domainHex == null) {
			return null;
		}
		final String expect = domainHex + NAME_SEP;
		if (!fileName.startsWith(expect)) {
			return null;
		}
		final int shardStart = expect.length();
		final int shardEnd = fileName.indexOf(NAME_SEP, shardStart);
		if (shardEnd <= shardStart) {
			return null;
		}
		try {
			return Integer.parseInt(fileName.substring(shardStart, shardEnd));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

}
