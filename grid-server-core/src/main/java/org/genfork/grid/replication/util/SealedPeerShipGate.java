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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skip-unchanged sealed baseline ship gate keyed by peer + domain#shard fingerprint.
 * <p>
 * Distinct from migrator {@code SealedPackShipGate} (CATCH_UP cycle once-per-entry);
 * HELLO / integrity / NACK donor paths use this peer-aware skip.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SealedPeerShipGate {

	private static final char KEY_SEP = '#';

	private final ConcurrentHashMap<String, AtomicLong> lastFingerprintByPeerStream = new ConcurrentHashMap<>();

	/**
	 * {@code true} when pack should ship (fingerprint changed or never shipped to peer).
	 */
	public boolean shouldShip(String peerId, String domainType, int shard, long fingerprint) {
		if (peerId == null || domainType == null) {
			return false;
		}
		final AtomicLong prev = lastFingerprintByPeerStream.get(key(peerId, domainType, shard));
		return prev == null || prev.get() != fingerprint;
	}

	/**
	 * Record that {@code peerId} has applied / advertised {@code fingerprint}
	 * (not merely that bytes were pushed).
	 */
	public void onShipped(String peerId, String domainType, int shard, long fingerprint) {
		if (peerId == null || domainType == null) {
			return;
		}
		lastFingerprintByPeerStream
				.computeIfAbsent(key(peerId, domainType, shard), ignored -> new AtomicLong(0L))
				.set(fingerprint);
	}

	/**
	 * Clear gate so the same fingerprint may ship again (peer still incomplete).
	 */
	public void clear(String peerId, String domainType, int shard) {
		if (peerId == null || domainType == null) {
			return;
		}
		lastFingerprintByPeerStream.remove(key(peerId, domainType, shard));
	}

	private static String key(String peerId, String domainType, int shard) {
		return peerId + KEY_SEP + domainType + KEY_SEP + shard;
	}
}
