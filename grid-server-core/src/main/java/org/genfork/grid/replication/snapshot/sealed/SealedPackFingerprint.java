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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;

/**
 * Cheap fingerprint of on-disk sealed shard artifacts (mtime + size), for skip-if-unchanged ship.
 * <p>
 * Wire bytes / paths only — no decoded Object graph.
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
	 * Stable long over listed artifact files; {@code 0} when the list is empty or root missing.
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
	 * Fingerprint for {@code domain#shard} under {@code sealedRoot}; {@code 0} when no artifacts.
	 */
	public static long ofShard(Path sealedRoot, String domain, int shard) throws IOException {
		final List<Path> files = SealedShardPack.listShardFiles(sealedRoot, domain, shard);
		return ofFiles(files);
	}

	private static long mix(long hash, long value) {
		long x = hash ^ (value * MIX_A);
		x = (x ^ (x >>> 30)) * MIX_B;
		return x ^ (x >>> 27);
	}
}
