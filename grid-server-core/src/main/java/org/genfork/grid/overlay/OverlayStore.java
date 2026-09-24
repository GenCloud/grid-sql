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
package org.genfork.grid.overlay;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.utils.ArrayUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-time overlays with optional durable sidecar under {@code overlay/}.
 * <p>
 * Product pin/QoS path for AdaptiveReplicaSwarm ({@link #hasAnyPinned()}) — not a second row store.
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class OverlayStore {
	private final Map<String, OverlayAnnotation> byKey = new ConcurrentHashMap<>();
	private final boolean enabled;
	private final Path durableDir;

	public OverlayStore(boolean enabled) {
		this(enabled, null);
	}

	public OverlayStore(boolean enabled, Path durableDir) {
		this.enabled = enabled;
		this.durableDir = durableDir;
		if (enabled && durableDir != null) {
			try {
				GridFs.createDirs(durableDir);
				loadAll();
			} catch (IOException e) {
				throw new IllegalStateException("overlay dir", e);
			}
		}
	}

	private static String key(String domainType, long keyHash) {
		return domainType + "#" + keyHash;
	}

	public void put(String domainType, byte[] keyBytes, Long ttlHintMs, boolean pin, String qosTag) {
		if (!enabled || domainType == null || keyBytes == null) {
			return;
		}
		final long hash = ArrayUtil.fastHash(keyBytes) & 0xffffffffL;
		put(domainType, hash, ttlHintMs, pin, qosTag);
	}

	public void put(String domainType, long keyHash, Long ttlHintMs, boolean pin, String qosTag) {
		if (!enabled || domainType == null) {
			return;
		}
		final OverlayAnnotation ann = new OverlayAnnotation(
				domainType, keyHash, ttlHintMs, pin, qosTag, System.currentTimeMillis()
		);
		final String mapKey = key(domainType, keyHash);
		final OverlayAnnotation prev = byKey.put(mapKey, ann);
		adjustPinnedMetric(prev, ann);
		persist(ann);
	}

	/**
	 * Optional auto-pin on hot write when {@code autoPinTtlMs > 0}. No-op when overlay disabled or TTL off.
	 */
	public void autoPinOnHotWrite(String domainType, byte[] keyBytes, long autoPinTtlMs) {
		if (!enabled || autoPinTtlMs <= 0L || domainType == null || keyBytes == null) {
			return;
		}
		put(domainType, keyBytes, autoPinTtlMs, true, null);
	}

	public boolean remove(String domainType, byte[] keyBytes) {
		if (!enabled || domainType == null || keyBytes == null) {
			return false;
		}
		final long hash = ArrayUtil.fastHash(keyBytes) & 0xffffffffL;
		return remove(domainType, hash);
	}

	public boolean remove(String domainType, long keyHash) {
		if (!enabled || domainType == null) {
			return false;
		}
		final OverlayAnnotation prev = byKey.remove(key(domainType, keyHash));
		if (prev == null) {
			return false;
		}
		adjustPinnedMetric(prev, null);
		deleteDurable(prev);
		return true;
	}

	public Optional<OverlayAnnotation> get(String domainType, long keyHash) {
		if (!enabled) {
			return Optional.empty();
		}
		final String mapKey = key(domainType, keyHash);
		final OverlayAnnotation a = byKey.get(mapKey);
		if (a == null) {
			return Optional.empty();
		}
		if (isExpired(a)) {
			if (byKey.remove(mapKey, a)) {
				adjustPinnedMetric(a, null);
				deleteDurable(a);
			}
			return Optional.empty();
		}
		return Optional.of(a);
	}

	public Optional<OverlayAnnotation> get(String domainType, byte[] keyBytes) {
		if (!enabled || domainType == null || keyBytes == null) {
			return Optional.empty();
		}
		final long hash = ArrayUtil.fastHash(keyBytes) & 0xffffffffL;
		return get(domainType, hash);
	}

	public static boolean isExpired(OverlayAnnotation a) {
		if (a == null || a.ttlHintMs() == null || a.ttlHintMs() <= 0) {
			return false;
		}
		return System.currentTimeMillis() - a.updatedAtMs() > a.ttlHintMs();
	}


	/**
	 * True when a live pin for {@code domainType} hashes to {@code shard}
	 * using the same rule as {@code TableStore}: {@code Math.abs((int) keyHash % shardCount)}.
	 */
	public boolean pinsDomainShard(String domainType, int shard, int shardCount) {
		if (!enabled || domainType == null || shardCount <= 0 || shard < 0 || shard >= shardCount) {
			return false;
		}
		for (OverlayAnnotation a : byKey.values()) {
			if (a == null || !domainType.equals(a.domainType())) {
				continue;
			}
			if (isExpired(a)) {
				continue;
			}
			if (!a.pin()) {
				continue;
			}
			final int pinnedShard = Math.abs((int) a.keyHash() % shardCount);
			if (pinnedShard == shard) {
				return true;
			}
		}
		return false;
	}
	public boolean hasAnyPinned() {
		if (!enabled) {
			return false;
		}
		for (Map.Entry<String, OverlayAnnotation> e : byKey.entrySet()) {
			final OverlayAnnotation a = e.getValue();
			if (a == null) {
				continue;
			}
			if (isExpired(a)) {
				if (byKey.remove(e.getKey(), a)) {
					adjustPinnedMetric(a, null);
					deleteDurable(a);
				}
				continue;
			}
			if (a.pin()) {
				return true;
			}
		}
		return false;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int size() {
		return byKey.size();
	}

	private static void adjustPinnedMetric(OverlayAnnotation prev, OverlayAnnotation next) {
		final boolean wasPinned = prev != null && prev.pin() && !isExpired(prev);
		final boolean nowPinned = next != null && next.pin() && !isExpired(next);
		if (wasPinned == nowPinned) {
			return;
		}
		ReplicationMetrics.recordOverlayPinnedKeysDelta(nowPinned ? 1L : -1L);
	}

	private void persist(OverlayAnnotation ann) {
		if (durableDir == null || ann == null) {
			return;
		}
		try {
			final Path file = durableFile(ann);
			final String body = ann.domainType() + "\n" + ann.keyHash() + "\n"
					+ (ann.ttlHintMs() == null ? "" : ann.ttlHintMs()) + "\n"
					+ ann.pin() + "\n"
					+ (ann.qosTag() == null ? "" : ann.qosTag()) + "\n"
					+ ann.updatedAtMs();
			GridFs.writeAtomic(file, body, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}

	private void deleteDurable(OverlayAnnotation ann) {
		if (durableDir == null || ann == null) {
			return;
		}
		try {
			GridFs.deleteIfExists(durableFile(ann));
		} catch (IOException ignored) {
		}
	}

	private Path durableFile(OverlayAnnotation ann) {
		return durableDir.resolve(Integer.toHexString(ann.domainType().hashCode())
				+ "_" + Long.toHexString(ann.keyHash()) + ".ovl");
	}

	private void loadAll() throws IOException {
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(durableDir, "*.ovl")) {
			for (Path p : stream) {
				try {
					final String[] lines = GridFs.readString(p, StandardCharsets.UTF_8).split("\n");
					if (lines.length < 6) {
						continue;
					}
					final String domain = lines[0];
					final long hash = Long.parseLong(lines[1].trim());
					final Long ttl = lines[2].isBlank() ? null : Long.parseLong(lines[2].trim());
					final boolean pin = Boolean.parseBoolean(lines[3].trim());
					final String qos = lines[4].isBlank() ? null : lines[4];
					final long updated = Long.parseLong(lines[5].trim());
					final OverlayAnnotation ann = new OverlayAnnotation(domain, hash, ttl, pin, qos, updated);
					final OverlayAnnotation prev = byKey.put(key(domain, hash), ann);
					adjustPinnedMetric(prev, ann);
				} catch (Exception ignored) {
				}
			}
		}
	}
}
