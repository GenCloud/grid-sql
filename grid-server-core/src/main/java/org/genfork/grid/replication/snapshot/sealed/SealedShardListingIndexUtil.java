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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide durable sealed-root listing index (one DirectoryStream per epoch).
 * <p>
 * Hot paths via {@link SealedShardPack#listShardFiles} (lookup + unindexed fallback), inventory,
 * sensor, HELLO observe look up {@code domainHex_shard} prefixes without Windows FindNextFile
 * when the installed snapshot matches {@code sealedRoot}. Invalidate / rebuild on structural
 * hole epoch bumps.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SealedShardListingIndexUtil {
	private static final String GMAP_SUFFIX = ".gmap";
	private static final String SBPT_SUFFIX = ".sbpt";
	private static final String SBM_SUFFIX = ".sbm";
	private static final char NAME_SEP = '_';

	private static final AtomicReference<Snapshot> INSTALLED = new AtomicReference<>();

	private SealedShardListingIndexUtil() {
	}

	/**
	 * Drop the installed snapshot (next {@link #listShardFiles} falls back to DirectoryStream
	 * until {@link #rebuildAndInstall}).
	 */
	public static void invalidate() {
		INSTALLED.set(null);
	}

	/**
	 * One full sealed-root scan → immutable prefix map; installs as current snapshot.
	 *
	 * @return number of distinct {@code hex_shard} prefixes indexed
	 */
	public static int rebuildAndInstall(Path sealedRoot) throws IOException {
		Objects.requireNonNull(sealedRoot, "sealedRoot");
		final Snapshot snap = build(sealedRoot);
		INSTALLED.set(snap);
		return snap.byPrefix.size();
	}

	/**
	 * Indexed listing when snapshot matches {@code sealedRoot}; otherwise {@code null}
	 * (caller must DirectoryStream).
	 */
	public static List<Path> lookupShardFiles(Path sealedRoot, String domain, int shard) {
		Objects.requireNonNull(sealedRoot, "sealedRoot");
		Objects.requireNonNull(domain, "domain");
		final Snapshot snap = INSTALLED.get();
		if (snap == null || !snap.rootEquals(sealedRoot)) {
			return null;
		}
		final String prefix = SealedShardPack.domainHex(domain) + NAME_SEP + shard;
		final List<Path> listed = snap.byPrefix.get(prefix);
		return listed == null ? List.of() : listed;
	}

	/**
	 * Live DirectoryStream list (same order contract as pack). Used when index cold / foreign root.
	 */
	public static List<Path> listShardFilesUnindexed(Path sealedRoot, String domain, int shard)
			throws IOException {
		Objects.requireNonNull(sealedRoot, "sealedRoot");
		Objects.requireNonNull(domain, "domain");
		if (!GridFs.isDirectory(sealedRoot)) {
			return List.of();
		}
		final String hex = SealedShardPack.domainHex(domain);
		final String prefix = hex + NAME_SEP + shard;
		final List<Path> nodeFiles = new ArrayList<>();
		final List<Path> indexFiles = new ArrayList<>();
		final List<Path> bitmapFiles = new ArrayList<>();
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(sealedRoot, prefix + "_*")) {
			for (Path file : stream) {
				classifyInto(file, nodeFiles, indexFiles, bitmapFiles);
			}
		}
		return orderCopy(nodeFiles, indexFiles, bitmapFiles);
	}

	static Snapshot build(Path sealedRoot) throws IOException {
		if (!GridFs.isDirectory(sealedRoot)) {
			return new Snapshot(sealedRoot.toAbsolutePath().normalize(), Map.of());
		}
		final Map<String, Bucket> buckets = new HashMap<>();
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(sealedRoot, "*")) {
			for (Path file : stream) {
				if (!GridFs.isRegularFile(file) || GridFs.isOrphanStagingName(file)) {
					continue;
				}
				final String name = file.getFileName() == null ? "" : file.getFileName().toString();
				final String prefix = shardPrefixOf(name);
				if (prefix == null) {
					continue;
				}
				final Bucket bucket = buckets.computeIfAbsent(prefix, ignored -> new Bucket());
				if (name.endsWith(GMAP_SUFFIX)) {
					bucket.gmap.add(file);
				} else if (name.endsWith(SBPT_SUFFIX)) {
					bucket.sbpt.add(file);
				} else if (name.endsWith(SBM_SUFFIX)) {
					bucket.sbm.add(file);
				}
			}
		}
		final Map<String, List<Path>> byPrefix = new HashMap<>(buckets.size());
		for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
			final Bucket bucket = entry.getValue();
			byPrefix.put(entry.getKey(), orderCopy(bucket.gmap, bucket.sbpt, bucket.sbm));
		}
		return new Snapshot(sealedRoot.toAbsolutePath().normalize(), Map.copyOf(byPrefix));
	}

	/**
	 * {@code hex_shard} from {@code hex_shard_n0.gmap} / {@code hex_shard_idx.sbpt} / etc.
	 */
	public static String shardPrefixOf(String fileName) {
		if (fileName == null || fileName.isEmpty()) {
			return null;
		}
		final int first = fileName.indexOf(NAME_SEP);
		if (first <= 0) {
			return null;
		}
		final int second = fileName.indexOf(NAME_SEP, first + 1);
		if (second <= first + 1) {
			return null;
		}
		final String shardToken = fileName.substring(first + 1, second);
		for (int i = 0; i < shardToken.length(); i++) {
			final char c = shardToken.charAt(i);
			if (c < '0' || c > '9') {
				return null;
			}
		}
		if (!fileName.endsWith(GMAP_SUFFIX)
				&& !fileName.endsWith(SBPT_SUFFIX)
				&& !fileName.endsWith(SBM_SUFFIX)) {
			return null;
		}
		return fileName.substring(0, second);
	}

	private static void classifyInto(
			Path file,
			List<Path> nodeFiles,
			List<Path> indexFiles,
			List<Path> bitmapFiles
	) {
		if (!GridFs.isRegularFile(file) || GridFs.isOrphanStagingName(file)) {
			return;
		}
		final String name = file.getFileName() == null ? "" : file.getFileName().toString();
		if (name.endsWith(GMAP_SUFFIX)) {
			nodeFiles.add(file);
		} else if (name.endsWith(SBPT_SUFFIX)) {
			indexFiles.add(file);
		} else if (name.endsWith(SBM_SUFFIX)) {
			bitmapFiles.add(file);
		}
	}

	private static List<Path> orderCopy(
			List<Path> nodeFiles,
			List<Path> indexFiles,
			List<Path> bitmapFiles
	) {
		nodeFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
		indexFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
		bitmapFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
		final List<Path> ordered = new ArrayList<>(
				nodeFiles.size() + indexFiles.size() + bitmapFiles.size());
		ordered.addAll(nodeFiles);
		ordered.addAll(indexFiles);
		ordered.addAll(bitmapFiles);
		return List.copyOf(ordered);
	}

	private static final class Bucket {
		private final List<Path> gmap = new ArrayList<>();
		private final List<Path> sbpt = new ArrayList<>();
		private final List<Path> sbm = new ArrayList<>();
	}

	static final class Snapshot {
		private final Path rootAbsolute;
		private final Map<String, List<Path>> byPrefix;

		private Snapshot(Path rootAbsolute, Map<String, List<Path>> byPrefix) {
			this.rootAbsolute = rootAbsolute;
			this.byPrefix = byPrefix == null ? Map.of() : byPrefix;
		}

		private boolean rootEquals(Path sealedRoot) {
			try {
				return rootAbsolute.equals(sealedRoot.toAbsolutePath().normalize());
			} catch (Exception ignored) {
				return false;
			}
		}

	}
}
