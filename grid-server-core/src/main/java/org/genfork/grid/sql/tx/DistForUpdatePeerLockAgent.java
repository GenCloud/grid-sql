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

/**
 * Peer-side row lock acquire/release for distributed {@code FOR UPDATE} (Phase 3 v1).
 * <p>
 * Implementations must park only on a logic VT (never Netty EL) — typically by calling
 * {@link SqlRecordLockManager} on the peer node. Wire keys stay {@code byte[]}.
 * <p>
 * Product Netty path may wrap replication {@code FOR_UPDATE_LOCK_*} RPCs; tests use
 * {@link InProcessDistForUpdatePeerLockAgent}.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public interface DistForUpdatePeerLockAgent {
	/**
	 * Acquire a peer row lock for {@code table}/{@code key} under {@code txId}.
	 *
	 * @param skipLocked when true use non-blocking try; else blocking wait
	 * @return {@code true} when held; {@code false} only for skip-locked miss
	 */
	boolean lock(long txId, String table, byte[] key, boolean skipLocked);

	/** Release a previously acquired peer lock (idempotent). */
	void unlock(long txId, String table, byte[] key);
}