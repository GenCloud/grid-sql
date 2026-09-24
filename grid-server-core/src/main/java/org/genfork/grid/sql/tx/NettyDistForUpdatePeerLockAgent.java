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

import org.genfork.grid.replication.netty.NettyReplicationTransport;

/**
 * Netty peer lock agent for distributed {@code FOR UPDATE}.
 * <p>
 * Delegates to {@link NettyReplicationTransport}{@code FOR_UPDATE_LOCK_*} RPCs.
 * Callers must invoke {@link #lock}/{@link #unlock} on a logic VT only (never Netty EL);
 * the transport parks the waiter with {@code CompletableFuture.get} on that same thread.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class NettyDistForUpdatePeerLockAgent implements DistForUpdatePeerLockAgent {
	private final NettyReplicationTransport transport;
	private final String peerId;

	public NettyDistForUpdatePeerLockAgent(NettyReplicationTransport transport, String peerId) {
		this.transport = Objects.requireNonNull(transport, "transport");
		this.peerId = Objects.requireNonNull(peerId, "peerId");
		if (peerId.isBlank()) {
			throw new IllegalArgumentException("peerId required");
		}
	}

	@Override
	public boolean lock(long txId, String table, byte[] key, boolean skipLocked) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		return transport.sendForUpdateLockReq(peerId, txId, skipLocked, table, key);
	}

	@Override
	public void unlock(long txId, String table, byte[] key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		transport.sendForUpdateLockRelease(peerId, txId, table, key);
	}

	public String peerId() {
		return peerId;
	}
}
