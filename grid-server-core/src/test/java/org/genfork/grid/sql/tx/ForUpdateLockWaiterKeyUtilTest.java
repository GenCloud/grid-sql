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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Dist FOR UPDATE waiter correlator uniqueness.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class ForUpdateLockWaiterKeyUtilTest {
	private static final String PEER = "peer-a";
	private static final long TX_ID = 9L;
	private static final String TABLE = "t";
	private static final byte[] KEY_A = new byte[]{1, 2};
	private static final byte[] KEY_B = new byte[]{3, 4};

	@Test
	void releaseKeysDifferPerRow() {
		final String a = ForUpdateLockWaiterKeyUtil.releaseKey(PEER, TX_ID, TABLE, KEY_A);
		final String b = ForUpdateLockWaiterKeyUtil.releaseKey(PEER, TX_ID, TABLE, KEY_B);
		assertNotEquals(a, b);
		assertEquals(a, ForUpdateLockWaiterKeyUtil.releaseKey(PEER, TX_ID, TABLE, KEY_A));
	}

	@Test
	void lockOrPrepareStable() {
		assertEquals(
				ForUpdateLockWaiterKeyUtil.lockOrPrepareKey(PEER, TX_ID),
				ForUpdateLockWaiterKeyUtil.lockOrPrepareKey(PEER, TX_ID));
	}
}