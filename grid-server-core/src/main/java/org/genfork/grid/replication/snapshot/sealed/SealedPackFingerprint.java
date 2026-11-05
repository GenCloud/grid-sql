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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Cheap fingerprint of on-disk sealed shard artifacts for skip-if-unchanged ship and peer compare.
 * <p>
 * Wire bytes / paths only — no decoded Object graph.
 * {@link #ofFiles} includes mtime (local migrate skip). {@link #ofFilesIdentity} is name+size only
 * (cross-host HELLO / integrity compare — mtime drifts after ship).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedPackFingerprint {
	private static final long EMPTY_TOKEN = 0L;
	private static final long MIX_A = 0x9E3779B97F4A7C15L;
	private static final long MIX_B = 0xBF58476D1CE4E5B9L;

	private SealedPackFingerprint() {
	}

	/**
	 * Stable long over listed artifact files including mtime; {@code 0} when empty / root missing.
	 */
	public static long ofFiles(List<Path> files) throws IOException {
		if (files == null || files.isEmpty()) {
			return EMPTY_TOKEN;
		}
		long hash = EMPTY_TOKEN;
		for (Path file : files) {
			Objects.requireNonNull(file, "file");
			if (!GridFs.exists(file)) {
				continue;
			}
			final BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
			final long size = attrs.size();
			final long mtime = attrs.lastModifiedTime().toMillis();
			final String name = file.getFileName().toString();
			hash = mix(hash, name.hashCode());
			hash = mix(hash, size);
			hash = mix(hash, mtime);
		}
		return hash;
	}

	/**
	 * Cross-host identity: name + size only (no mtime). {@code 0} when empty.
	 */
	public static long ofFilesIdentity(List<Path> files) throws IOException {
		if (files == null || files.isEmpty()) {
			return EMPTY_TOKEN;
		}
		long hash = EMPTY_TOKEN;
		for (Path file : files) {
			Objects.requireNonNull(file, "file");
			if (!GridFs.exists(file)) {
				continue;
			}
			final BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
			final String name = file.getFileName().toString();
			hash = mix(hash, name.hashCode());
			hash = mix(hash, attrs.size());
		}
		return hash;
	}

	/**
	 * Name+size identity for {@code domain#shard}; {@code 0} when no artifacts.
	 */
	public static long ofShardIdentity(Path sealedRoot, String domain, int shard) throws IOException {
		final List<Path> files = SealedShardPack.listShardFiles(sealedRoot, domain, shard);
		return ofFilesIdentity(files);
	}

	/**
	 * Name+size identity over <strong>all</strong> sealed artifacts under {@code sealedRoot}
	 * (every {@code *.gmap}/{@code *.sbpt}/{@code *.sbm}). Used for HELLO advertise so a node-hole
	 * is visible even when catalog domain keys do not match dump prefixes.
	 */
	public static long ofSealedRootIdentity(Path sealedRoot) throws IOException {
		if (!GridFs.isDirectory(sealedRoot)) {
			return EMPTY_TOKEN;
		}

		final List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(sealedRoot, "*")) {
			for (Path file : stream) {
				if (!GridFs.isRegularFile(file) || GridFs.isOrphanStagingName(file)) {
					continue;
				}
				final String name = file.getFileName().toString();
				if (name.endsWith(".gmap") || name.endsWith(".sbpt") || name.endsWith(".sbm")) {
					files.add(file);
				}
			}
		}
		files.sort(Comparator.comparing(p -> p.getFileName().toString()));
		return ofFilesIdentity(files);
	}

	/**
	 * Mix stream identity fingerprints into one HELLO advertise token (skips empty shard token).
	 */
	public static long mixIdentity(long aggregate, long shardIdentity) {
		if (shardIdentity == EMPTY_TOKEN) {
			return aggregate;
		}
		return mix(aggregate, shardIdentity);
	}

	/**
	 * Polynomial mix for name/size tokens (zeros are significant — do not skip).
	 */
	public static long mixTokens(long hash, long value) {
		return mix(hash, value);
	}

	private static long mix(long hash, long value) {
		long x = hash ^ (value * MIX_A);
		x = (x ^ (x >>> 30)) * MIX_B;
		return x ^ (x >>> 27);
	}
}
