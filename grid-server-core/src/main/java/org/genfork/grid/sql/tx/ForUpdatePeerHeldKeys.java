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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks peer-held FOR UPDATE row locks by {@code txId} for prepare votes and abort release.
 * <p>
 * Used only on logic VT (Netty inbound enqueue), never on the event loop.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class ForUpdatePeerHeldKeys {
	private final ConcurrentHashMap<Long, ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Boolean>>> byTx =
			new ConcurrentHashMap<>();

	public void remember(long txId, String table, byte[] key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		byTx.computeIfAbsent(txId, _ -> new ConcurrentHashMap<>())
				.computeIfAbsent(table, _ -> new ConcurrentHashMap<>())
				.put(new KeyWrapper(key), Boolean.TRUE);
	}

	public void forget(long txId, String table, byte[] key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		final ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Boolean>> tables = byTx.get(txId);
		if (tables == null) {
			return;
		}
		final ConcurrentHashMap<KeyWrapper, Boolean> keys = tables.get(table);
		if (keys == null) {
			return;
		}
		keys.remove(new KeyWrapper(key));
		if (keys.isEmpty()) {
			tables.remove(table, keys);
		}
		if (tables.isEmpty()) {
			byTx.remove(txId, tables);
		}
	}

	public boolean hasAny(long txId) {
		final ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Boolean>> tables = byTx.get(txId);
		if (tables == null || tables.isEmpty()) {
			return false;
		}
		for (ConcurrentHashMap<KeyWrapper, Boolean> keys : tables.values()) {
			if (keys != null && !keys.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Unlock all remembered keys for {@code txId} and drop the tracking entry (idempotent).
	 */
	public void releaseAll(long txId, SqlRecordLockManager locks) {
		final ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Boolean>> tables = byTx.remove(txId);
		if (tables == null || locks == null) {
			return;
		}
		for (Map.Entry<String, ConcurrentHashMap<KeyWrapper, Boolean>> te : tables.entrySet()) {
			final String table = te.getKey();
			final ConcurrentHashMap<KeyWrapper, Boolean> keys = te.getValue();
			if (keys == null) {
				continue;
			}
			for (KeyWrapper kw : keys.keySet()) {
				try {
					locks.unlock(table, kw.key());
				} catch (RuntimeException ignored) {
					// idempotent
				}
			}
		}
	}

	public void clear() {
		byTx.clear();
	}
}