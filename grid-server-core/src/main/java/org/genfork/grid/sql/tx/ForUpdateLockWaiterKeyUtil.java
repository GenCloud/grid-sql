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

import java.util.HexFormat;
import java.util.Objects;

/**
 * Correlator keys for Dist FOR UPDATE Netty waiters (lock / prepare / release ACK).
 * <p>
 * Release waiters are keyed by {@code peerId + txId + table + keyBytes} so multi-key abort
 * on the same peer does not collide. Lock/prepare stay per peer/tx (one in-flight each).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class ForUpdateLockWaiterKeyUtil {

	/** Same separator as historical lock/prepare correlator in Netty transport. */
	private static final String SEP = "@";
	private static final String RELEASE_NS = "rel";
	private static final HexFormat KEY_HEX = HexFormat.of();

	private ForUpdateLockWaiterKeyUtil() {
	}

	public static String lockOrPrepareKey(String peerId, long txId) {
		return peerId + SEP + txId;
	}

	/**
	 * Release ACK correlator (unique per row).
	 */
	public static String releaseKey(String peerId, long txId, String table, byte[] key) {
		Objects.requireNonNull(peerId, "peerId");
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		return peerId + SEP + txId + SEP + RELEASE_NS + SEP + table + SEP + KEY_HEX.formatHex(key);
	}
}
