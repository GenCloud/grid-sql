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
package org.genfork.grid.replication.util;

import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal sealed artifact header sensor (magic / truncated header) — not a full scrub.
 * <p>
 * Debounce ≤1 pack request per shard per window. LAZY callers should only probe opened shards.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SealedIntegritySensorUtil {

	/** Fixed GMAP header size probed for magic (see {@code SealedGridMapReader}). */
	public static final int GMAP_FIXED_HEADER_BYTES = 32;

	/** Default debounce between pack requests for the same stream. */
	public static final long PACK_REQUEST_DEBOUNCE_MS = 5_000L;

	private SealedIntegritySensorUtil() {
	}

	/**
	 * Probe result for one sealed {@code .gmap} path.
	 */
	public enum Probe {
		OK,
		MISSING,
		TRUNCATED,
		BAD_MAGIC,
		IO_ERROR
	}

	/**
	 * Read magic from {@code gmapPath}; fail-closed on truncate / bad magic / IO.
	 */
	public static Probe probeGmapHeader(Path gmapPath) {
		if (gmapPath == null || !Files.isRegularFile(gmapPath)) {
			return Probe.MISSING;
		}
		try {
			final long size = Files.size(gmapPath);
			if (size < GMAP_FIXED_HEADER_BYTES) {
				return Probe.TRUNCATED;
			}
			try (FileChannel channel = FileChannel.open(gmapPath, StandardOpenOption.READ)) {
				final ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
				final int read = channel.read(buf, 0L);
				if (read < Integer.BYTES) {
					return Probe.TRUNCATED;
				}
				buf.flip();
				final int magic = buf.getInt();
				if (magic != SealedGridMapWriter.MAGIC) {
					return Probe.BAD_MAGIC;
				}
				return Probe.OK;
			}
		} catch (IOException ignored) {
			return Probe.IO_ERROR;
		}
	}

	/**
	 * {@code true} when probe warrants a peer sealed pack pull.
	 */
	public static boolean needsPeerPack(Probe probe) {
		return probe == Probe.MISSING
				|| probe == Probe.TRUNCATED
				|| probe == Probe.BAD_MAGIC
				|| probe == Probe.IO_ERROR;
	}

	/**
	 * Debounce state for pack requests.
	 */
	public static final class PackDebounce {
		private final ConcurrentHashMap<String, Long> lastRequestMillis = new ConcurrentHashMap<>();

		public boolean tryAcquire(String domainType, int shard, long nowMillis, long debounceMs) {
			Objects.requireNonNull(domainType, "domainType");
			final String key = domainType + '#' + shard;
			final long window = debounceMs <= 0L ? PACK_REQUEST_DEBOUNCE_MS : debounceMs;
			final Long prev = lastRequestMillis.get(key);
			if (prev != null && (nowMillis - prev.longValue()) < window) {
				return false;
			}
			lastRequestMillis.put(key, nowMillis);
			return true;
		}
	}
}
