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
package index.unit.replication;

import org.genfork.grid.replication.util.SealedPeerShipGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ship gate: confirm via onShipped; in-flight TTL debounce; clear allows reship.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedPeerShipGateTest {

	private static final String PEER = "n2";
	private static final String DOMAIN = "d";
	private static final int SHARD = 0;
	private static final long FP = 9L;
	private static final long T0 = 1_000_000L;
	private static final long TTL = 15_000L;

	@Test
	void clearAllowsReshipOfSameFingerprint() {
		final SealedPeerShipGate gate = new SealedPeerShipGate();
		assertTrue(gate.shouldShip(PEER, DOMAIN, SHARD, FP));
		gate.onShipped(PEER, DOMAIN, SHARD, FP);
		assertFalse(gate.shouldShip(PEER, DOMAIN, SHARD, FP));
		gate.clear(PEER, DOMAIN, SHARD);
		assertTrue(gate.shouldShip(PEER, DOMAIN, SHARD, FP));
	}

	@Test
	void notePushInFlightSuppressesUntilTtlThenAllowsReship() {
		final SealedPeerShipGate gate = new SealedPeerShipGate();
		assertTrue(gate.shouldShip(PEER, DOMAIN, SHARD, FP, T0));
		gate.notePushInFlight(PEER, DOMAIN, SHARD, FP, T0, TTL);
		assertFalse(gate.shouldShip(PEER, DOMAIN, SHARD, FP, T0 + 1L));
		assertTrue(gate.isActive(PEER, DOMAIN, SHARD, T0 + 1L));
		assertTrue(gate.shouldShip(PEER, DOMAIN, SHARD, FP, T0 + TTL));
		assertFalse(gate.isActive(PEER, DOMAIN, SHARD, T0 + TTL));
	}

	@Test
	void onShippedSurvivesInFlightExpiry() {
		final SealedPeerShipGate gate = new SealedPeerShipGate();
		gate.notePushInFlight(PEER, DOMAIN, SHARD, FP, T0, TTL);
		gate.onShipped(PEER, DOMAIN, SHARD, FP);
		assertFalse(gate.shouldShip(PEER, DOMAIN, SHARD, FP, T0 + TTL + 1L));
		assertTrue(gate.isActive(PEER, DOMAIN, SHARD, T0 + TTL + 1L));
	}

	@Test
	void notePushInFlightDoesNotOverrideConfirmed() {
		final SealedPeerShipGate gate = new SealedPeerShipGate();
		gate.onShipped(PEER, DOMAIN, SHARD, FP);
		gate.notePushInFlight(PEER, DOMAIN, SHARD, FP, T0, TTL);
		assertFalse(gate.shouldShip(PEER, DOMAIN, SHARD, FP, T0 + TTL + 1L));
	}

	@Test
	void differentFingerprintIgnoresPriorInFlight() {
		final SealedPeerShipGate gate = new SealedPeerShipGate();
		gate.notePushInFlight(PEER, DOMAIN, SHARD, FP, T0, TTL);
		assertTrue(gate.shouldShip(PEER, DOMAIN, SHARD, FP + 1L, T0 + 1L));
	}
}
