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

import java.util.Objects;

import com.google.common.annotations.VisibleForTesting;

/**
 * In-process peer lock agent: delegates to another node's {@link SqlRecordLockManager}.
 * <p>
 * Used by IT to prove cross-node lock wait without Netty; production may use the same
 * manager behind a logic-VT RPC handler.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class InProcessDistForUpdatePeerLockAgent implements DistForUpdatePeerLockAgent {
	private final SqlRecordLockManager locks;

	@VisibleForTesting
	public InProcessDistForUpdatePeerLockAgent(SqlRecordLockManager locks) {
		this.locks = Objects.requireNonNull(locks, "locks");
	}

	@Override
	public boolean lock(long txId, String table, byte[] key, boolean skipLocked) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		if (skipLocked) {
			return locks.tryLock(table, key);
		}
		locks.lock(table, key);
		return true;
	}

	@Override
	public void unlock(long txId, String table, byte[] key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		locks.unlock(table, key);
	}

	@VisibleForTesting
	public SqlRecordLockManager lockManager() {
		return locks;
	}
}