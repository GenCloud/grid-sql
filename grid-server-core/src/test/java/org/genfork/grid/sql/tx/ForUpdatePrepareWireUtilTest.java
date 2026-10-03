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

import org.genfork.grid.sql.tx.ForUpdatePrepareWireUtil.DecodedPrepareReq;
import org.genfork.grid.sql.tx.ForUpdatePrepareWireUtil.TableKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire encode/decode for Dist FOR UPDATE prepare key-set.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class ForUpdatePrepareWireUtilTest {
	private static final long TX_ID = 42L;
	private static final String NODE = "coord";
	private static final String TABLE_A = "t_a";
	private static final String TABLE_B = "t_b";
	private static final byte[] KEY_A = new byte[]{1, 2};
	private static final byte[] KEY_B = new byte[]{9};

	@Test
	void roundTripWithKeys() {
		final List<TableKey> keys = List.of(
				new TableKey(TABLE_A, KEY_A),
				new TableKey(TABLE_B, KEY_B));
		final byte[] wire = ForUpdatePrepareWireUtil.encodeReq(TX_ID, NODE, keys);
		final DecodedPrepareReq decoded = ForUpdatePrepareWireUtil.decodeReq(wire);
		assertEquals(TX_ID, decoded.txId());
		assertEquals(NODE, decoded.fromNodeId());
		assertEquals(2, decoded.keys().size());
		assertEquals(TABLE_A, decoded.keys().get(0).table());
		assertArrayEquals(KEY_A, decoded.keys().get(0).key());
		assertEquals(TABLE_B, decoded.keys().get(1).table());
		assertArrayEquals(KEY_B, decoded.keys().get(1).key());
	}

	@Test
	void emptyKeySetRoundTrip() {
		final byte[] wire = ForUpdatePrepareWireUtil.encodeReq(TX_ID, NODE, List.of());
		final DecodedPrepareReq decoded = ForUpdatePrepareWireUtil.decodeReq(wire);
		assertEquals(TX_ID, decoded.txId());
		assertTrue(decoded.keys().isEmpty());
	}
}