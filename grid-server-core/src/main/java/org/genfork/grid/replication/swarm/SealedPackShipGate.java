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
package org.genfork.grid.replication.swarm;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-stream sealed pack ship gate: ship once per CATCH_UP entry unless fingerprint changes.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class SealedPackShipGate {
	private final ConcurrentHashMap<String, StreamShipState> byStream = new ConcurrentHashMap<>();

	/**
	 * Mark that a CATCH_UP cycle began — next ship is allowed even if fingerprint matches
	 * a prior cycle's last ship (caller still applies fingerprint skip within the cycle).
	 */
	public void onCatchUpEntered(String domainType, int shard) {
		streamState(domainType, shard).catchUpShipDone.set(false);
	}

	/**
	 * {@code true} when sealed should be packed/pushed for this CATCH_UP tick.
	 */
	public boolean shouldShip(String domainType, int shard, long fingerprint) {
		final StreamShipState state = streamState(domainType, shard);
		if (state.catchUpShipDone.get() && state.lastFingerprint.get() == fingerprint) {
			return false;
		}
		return true;
	}

	/**
	 * Record a successful sealed ship (or intentional skip of empty pack) for this CATCH_UP.
	 */
	public void onShipped(String domainType, int shard, long fingerprint) {
		final StreamShipState state = streamState(domainType, shard);
		state.lastFingerprint.set(fingerprint);
		state.catchUpShipDone.set(true);
	}

	/**
	 * Reset ship-once flag when drain leaves CATCH_UP (cutover / abort).
	 */
	public void onDrainFinished(String domainType, int shard) {
		final StreamShipState state = byStream.get(streamKey(domainType, shard));
		if (state != null) {
			state.catchUpShipDone.set(false);
		}
	}

	private StreamShipState streamState(String domainType, int shard) {
		return byStream.computeIfAbsent(streamKey(domainType, shard), k -> new StreamShipState());
	}

	private static String streamKey(String domainType, int shard) {
		return Objects.requireNonNull(domainType, "domainType") + "#" + shard;
	}

	private static final class StreamShipState {
		private final AtomicLong lastFingerprint = new AtomicLong(0L);
		private final AtomicBoolean catchUpShipDone = new AtomicBoolean(false);
	}
}
