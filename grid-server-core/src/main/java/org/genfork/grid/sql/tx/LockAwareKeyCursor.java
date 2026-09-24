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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;

/**
 * Stream plan candidate keys and acquire row locks in one pass (FOR UPDATE / SKIP LOCKED).
 * <p>
 * Does not embed lock state in BPTree; {@link SqlRecordLockManager} remains the lock SoT.
 * Park/wait only on logic VT via the lock manager.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class LockAwareKeyCursor {
	private LockAwareKeyCursor() {
	}

	/**
	 * Acquired statement-scoped locks and keys selected after OFFSET / SKIP LOCKED / LIMIT.
	 */
	public record Batch(List<byte[]> statementLocks, List<byte[]> selectedKeys) {
	}

	/**
	 * FOR UPDATE over a store SELECT plan: stream keys then lock; stop when LIMIT filled.
	 *
	 * @param skipLocked when true use {@link SqlRecordLockManager#tryLock}; else blocking lock
	 * @param offset     rows to skip after a successful lock (or already-held TX lock)
	 * @param limitOrNull null = no limit
	 */
	public static Batch acquireFromSelect(
			SqlSession session,
			String table,
			TableStore store,
			String selectSql,
			boolean skipLocked,
			int offset,
			Integer limitOrNull
	) {
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(selectSql, "selectSql");
		final List<byte[]> acquired = new ArrayList<>();
		final List<byte[]> selected = new ArrayList<>();
		final int[] skipped = new int[]{0};
		try {
			store.forEachSelectKeys(selectSql, key -> {
				if (limitOrNull != null && selected.size() >= limitOrNull) {
					return false;
				}
				final KeyWrapper wrapped = new KeyWrapper(key);
				if (session.inTransaction() && session.requireTx().holdsLock(table, wrapped)) {
					if (skipped[0]++ < offset) {
						return true;
					}
					selected.add(key);
					return limitOrNull == null || selected.size() < limitOrNull;
				}
				final boolean locked;
				if (skipLocked) {
					locked = session.lockManager().tryLock(table, key);
				} else {
					session.lockManager().lock(table, key);
					locked = true;
				}
				if (!locked) {
					return true;
				}
				if (skipped[0]++ < offset) {
					session.lockManager().unlock(table, key);
					return true;
				}
				selected.add(key);
				if (session.inTransaction()) {
					session.requireTx().rememberLock(table, wrapped);
				} else {
					acquired.add(key);
				}
				return limitOrNull == null || selected.size() < limitOrNull;
			});
			return new Batch(acquired, selected);
		} catch (RuntimeException ex) {
			for (byte[] key : acquired) {
				session.lockManager().unlock(table, key);
			}
			throw ex;
		}
	}
}