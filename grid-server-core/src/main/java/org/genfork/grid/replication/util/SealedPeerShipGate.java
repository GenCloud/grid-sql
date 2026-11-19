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

/**
 * Skip-unchanged sealed baseline ship gate keyed by peer + domain#shard fingerprint.
 * <p>
 * Distinct from migrator {@code SealedPackShipGate} (CATCH_UP cycle once-per-entry);
 * HELLO / integrity / NACK donor paths use this peer-aware skip.
 * <p>
 * In-flight debounce (TTL) suppresses reship while a push may still be unpacking on the peer;
 * {@link #onShipped} confirms after inventory match. Expired in-flight allows reship
 * (unpack fail / lost ACK) — never a permanent provisional mark on push alone.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SealedPeerShipGate {

	private static final char KEY_SEP = '#';

	/**
	 * Default in-flight window after a successful push (same order as integrity pack debounce,
	 * long enough for large sealed unpack + APPLY_ACK).
	 */
	public static final long IN_FLIGHT_TTL_MS = 15_000L;

	private final ConcurrentHashMap<String, GateState> stateByPeerStream = new ConcurrentHashMap<>();

	/**
	 * {@code true} when pack should ship (fingerprint changed, never noted, or in-flight TTL expired).
	 */
	public boolean shouldShip(String peerId, String domainType, int shard, long fingerprint) {
		return shouldShip(peerId, domainType, shard, fingerprint, System.currentTimeMillis());
	}

	/**
	 * Time-injectable {@link #shouldShip} for tests.
	 */
	public boolean shouldShip(String peerId, String domainType, int shard, long fingerprint, long nowMillis) {
		if (peerId == null || domainType == null) {
			return false;
		}
		final GateState prev = stateByPeerStream.get(key(peerId, domainType, shard));
		if (prev == null) {
			return true;
		}
		if (prev.fingerprint != fingerprint) {
			return true;
		}
		if (prev.confirmed) {
			return false;
		}
		return nowMillis >= prev.inFlightUntilMillis;
	}

	/**
	 * Record a successful push that is not yet confirmed by peer inventory / ACK.
	 * Suppresses reship of the same fingerprint until {@code nowMillis + ttlMs}.
	 */
	public void notePushInFlight(
			String peerId,
			String domainType,
			int shard,
			long fingerprint,
			long nowMillis,
			long ttlMs
	) {
		if (peerId == null || domainType == null || fingerprint == 0L) {
			return;
		}
		final long ttl = ttlMs <= 0L ? IN_FLIGHT_TTL_MS : ttlMs;
		final String k = key(peerId, domainType, shard);
		stateByPeerStream.compute(k, (ignored, prev) -> {
			if (prev != null && prev.confirmed && prev.fingerprint == fingerprint) {
				return prev;
			}
			return new GateState(fingerprint, false, nowMillis + ttl);
		});
	}

	/**
	 * {@link #notePushInFlight} with {@link #IN_FLIGHT_TTL_MS}.
	 */
	public void notePushInFlight(String peerId, String domainType, int shard, long fingerprint, long nowMillis) {
		notePushInFlight(peerId, domainType, shard, fingerprint, nowMillis, IN_FLIGHT_TTL_MS);
	}

	/**
	 * Record that {@code peerId} has applied / advertised {@code fingerprint}
	 * (not merely that bytes were pushed).
	 */
	public void onShipped(String peerId, String domainType, int shard, long fingerprint) {
		if (peerId == null || domainType == null) {
			return;
		}
		stateByPeerStream.put(key(peerId, domainType, shard), new GateState(fingerprint, true, 0L));
	}

	/**
	 * Clear gate so the same fingerprint may ship again (peer still incomplete).
	 */
	public void clear(String peerId, String domainType, int shard) {
		if (peerId == null || domainType == null) {
			return;
		}
		stateByPeerStream.remove(key(peerId, domainType, shard));
	}

	/**
	 * {@code true} when confirmed or in-flight window has not expired.
	 */
	public boolean isActive(String peerId, String domainType, int shard) {
		return isActive(peerId, domainType, shard, System.currentTimeMillis());
	}

	/**
	 * Time-injectable {@link #isActive}.
	 */
	public boolean isActive(String peerId, String domainType, int shard, long nowMillis) {
		if (peerId == null || domainType == null) {
			return false;
		}
		final GateState prev = stateByPeerStream.get(key(peerId, domainType, shard));
		if (prev == null) {
			return false;
		}
		if (prev.confirmed) {
			return true;
		}
		return nowMillis < prev.inFlightUntilMillis;
	}

	private static String key(String peerId, String domainType, int shard) {
		return peerId + KEY_SEP + domainType + KEY_SEP + shard;
	}

	private static final class GateState {
		private final long fingerprint;
		private final boolean confirmed;
		private final long inFlightUntilMillis;

		private GateState(long fingerprint, boolean confirmed, long inFlightUntilMillis) {
			this.fingerprint = fingerprint;
			this.confirmed = confirmed;
			this.inFlightUntilMillis = inFlightUntilMillis;
		}
	}
}
