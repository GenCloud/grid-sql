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
package org.genfork.grid.sql.tx;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.genfork.grid.metrics.SqlLockMetrics;
import org.genfork.grid.sql.tx.ForUpdatePrepareWireUtil.TableKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Peer-held key-set containsAll + lease TTL expire.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class ForUpdatePeerHeldKeysTest {
	private static final long TX_ID = 7L;
	private static final String TABLE = "fu_held";
	private static final byte[] KEY_A = new byte[]{1};
	private static final byte[] KEY_B = new byte[]{2};
	private static final long TTL_MS = 10L;
	private static final long PAST_MS = 1_000L;

	private ForUpdatePeerHeldKeys held;
	private SqlRecordLockManager locks;
	private AtomicLong clockNs;

	@BeforeEach
	void setUp() {
		held = new ForUpdatePeerHeldKeys();
		locks = new SqlRecordLockManager();
		held.bindLockManager(locks);
		clockNs = new AtomicLong(0L);
		held.setNanoClock(clockNs::get);
		held.setLeaseTtlMs(TTL_MS);
	}

	@Test
	void containsAllRequiresEveryRequestedKey() {
		held.remember(TX_ID, TABLE, KEY_A);
		assertTrue(held.containsAll(TX_ID, List.of(new TableKey(TABLE, KEY_A))));
		assertFalse(held.containsAll(TX_ID, List.of(
				new TableKey(TABLE, KEY_A),
				new TableKey(TABLE, KEY_B))));
		held.remember(TX_ID, TABLE, KEY_B);
		assertTrue(held.containsAll(TX_ID, List.of(
				new TableKey(TABLE, KEY_A),
				new TableKey(TABLE, KEY_B))));
	}

	@Test
	void containsAllEmptyKeySetIsNotPrepared() {
		held.remember(TX_ID, TABLE, KEY_A);
		assertFalse(held.containsAll(TX_ID, List.of()));
		assertFalse(held.containsAll(TX_ID, null));
	}

	@Test
	void leaseTtlExpiresAndUnlocks() {
		assertTrue(locks.tryLock(TABLE, KEY_A));
		held.remember(TX_ID, TABLE, KEY_A);
		final long before = SqlLockMetrics.peerLeaseExpired();
		clockNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(PAST_MS));
		held.sweepExpiredForTest();
		assertFalse(held.hasAny(TX_ID));
		assertTrue(locks.tryLock(TABLE, KEY_A));
		locks.unlock(TABLE, KEY_A);
		assertTrue(SqlLockMetrics.peerLeaseExpired() > before);
	}
}