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

/**
 * Held peer row lock for distributed {@code FOR UPDATE}; released on statement end or TX end.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class DistForUpdatePeerLockLease {
	private final DistForUpdatePeerLockAgent agent;
	private final long txId;
	private final String table;
	private final byte[] key;

	public DistForUpdatePeerLockLease(
			DistForUpdatePeerLockAgent agent,
			long txId,
			String table,
			byte[] key
	) {
		this.agent = Objects.requireNonNull(agent, "agent");
		this.txId = txId;
		this.table = Objects.requireNonNull(table, "table");
		this.key = Objects.requireNonNull(key, "key");
	}

	public void release() {
		agent.unlock(txId, table, key);
	}

	public DistForUpdatePeerLockAgent agent() {
		return agent;
	}

	public String table() {
		return table;
	}

	public byte[] key() {
		return key;
	}

	public long txId() {
		return txId;
	}
}