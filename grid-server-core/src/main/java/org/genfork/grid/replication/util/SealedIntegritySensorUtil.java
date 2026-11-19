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

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sealed artifact sensor: structural GMAP probe plus peer per-shard name+size FP mismatch.
 * <p>
 * Debounce ≤1 pack request per shard per window. LAZY callers should only probe opened shards.
 * Magic-OK undersize is {@link Probe#TRUNCATED} via {@link SealedGmapStructuralProbeUtil}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SealedIntegritySensorUtil {

	/** Fixed GMAP header size (see {@link SealedGmapStructuralProbeUtil#FIXED_HEADER_BYTES}). */
	public static final int GMAP_FIXED_HEADER_BYTES = SealedGmapStructuralProbeUtil.FIXED_HEADER_BYTES;

	/** Default debounce between pack requests for the same stream. */
	public static final long PACK_REQUEST_DEBOUNCE_MS = 5_000L;

	/** OpLog pull {@code fromSeqInclusive} used for sealed integrity heal of one shard. */
	public static final long SEALED_INTEGRITY_PULL_FROM_SEQ = 1L;

	private SealedIntegritySensorUtil() {
	}

	/**
	 * Probe result for one sealed {@code .gmap} path or shard-vs-peer compare.
	 */
	public enum Probe {
		OK,
		MISSING,
		TRUNCATED,
		BAD_MAGIC,
		IO_ERROR,
		/** Local name+size identity differs from peer HELLO sealed fingerprint. */
		SIZE_OR_FP_MISMATCH
	}

	/**
	 * Structural GMAP probe (magic + directory bounds vs file size).
	 */
	public static Probe probeGmapHeader(Path gmapPath) {
		return SealedGmapStructuralProbeUtil.probe(gmapPath);
	}

	private static final String GMAP_SUFFIX = ".gmap";

	/**
	 * Worst structural probe across every non-orphan {@code .gmap} for {@code domain#shard}.
	 * Empty listing → {@link Probe#MISSING}.
	 */
	public static Probe probeAllGmapHeaders(Path sealedRoot, String domain, int shard) {
		if (sealedRoot == null || domain == null) {
			return Probe.MISSING;
		}
		try {
			return probeAllGmapHeadersListed(SealedShardPack.listShardFiles(sealedRoot, domain, shard));
		} catch (IOException ignored) {
			return Probe.IO_ERROR;
		}
	}

	/**
	 * Same as {@link #probeAllGmapHeaders} over a pre-listed artifact set (avoids second DirectoryStream).
	 */
	public static Probe probeAllGmapHeadersListed(List<Path> files) {
		if (files == null || files.isEmpty()) {
			return Probe.MISSING;
		}
		Probe worst = null;
		for (Path file : files) {
			if (file == null) {
				continue;
			}
			final String name = file.getFileName() == null ? "" : file.getFileName().toString();
			if (!name.endsWith(GMAP_SUFFIX) || GridFs.isOrphanStagingName(file)) {
				continue;
			}
			final Probe probe = probeGmapHeader(file);
			if (worst == null || severity(probe) > severity(worst)) {
				worst = probe;
			}
		}
		return worst == null ? Probe.MISSING : worst;
	}

	/**
	 * Peer identity mismatch when both sides advertise a non-zero sealed fingerprint.
	 */
	public static Probe probeFingerprintMismatch(long localIdentityFp, long peerIdentityFp) {
		if (peerIdentityFp == 0L || localIdentityFp == 0L) {
			return Probe.OK;
		}
		if (localIdentityFp != peerIdentityFp) {
			return Probe.SIZE_OR_FP_MISMATCH;
		}
		return Probe.OK;
	}

	/**
	 * Combine local header probe with peer fingerprint compare (worst wins).
	 */
	public static Probe combine(Probe headerProbe, Probe fingerprintProbe) {
		final Probe left = headerProbe == null ? Probe.MISSING : headerProbe;
		final Probe right = fingerprintProbe == null ? Probe.OK : fingerprintProbe;
		return severity(right) > severity(left) ? right : left;
	}

	/**
	 * {@code true} when probe warrants a peer sealed pack pull.
	 */
	public static boolean needsPeerPack(Probe probe) {
		return probe == Probe.MISSING
				|| probe == Probe.TRUNCATED
				|| probe == Probe.BAD_MAGIC
				|| probe == Probe.IO_ERROR
				|| probe == Probe.SIZE_OR_FP_MISMATCH;
	}

	private static int severity(Probe probe) {
		return switch (probe) {
			case OK -> 0;
			case SIZE_OR_FP_MISMATCH -> 1;
			case TRUNCATED -> 2;
			case BAD_MAGIC -> 3;
			case MISSING -> 4;
			case IO_ERROR -> 5;
		};
	}

	/**
	 * Debounce state for pack requests.
	 */
	public static final class PackDebounce {
		private final ConcurrentHashMap<String, Long> lastRequestMillis = new ConcurrentHashMap<>();

		public boolean tryAcquire(String domainType, int shard, long nowMillis, long debounceMs) {
			Objects.requireNonNull(domainType, "domainType");
			final String key = OpLogStreamKeyUtil.format(domainType, shard);
			final long window = debounceMs <= 0L ? PACK_REQUEST_DEBOUNCE_MS : debounceMs;
			final Long prev = lastRequestMillis.get(key);
			if (prev != null && (nowMillis - prev) < window) {
				return false;
			}
			lastRequestMillis.put(key, nowMillis);
			return true;
		}
	}
}
