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
package org.genfork.grid.sql.exec;

import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.tx.KeyWrapper;
import org.genfork.grid.sql.tx.SqlTxBuffer;
import org.genfork.grid.store.TableStore;

/**
 * TX row-lock / staging / dirty-overlay read helpers for {@link SqlDmlExecutor}.
 * <p>
 * Acquire locks before RMW read (TOCTOU). Staging always locks the encoded key.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlDmlLockOps {
	private SqlDmlLockOps() {
	}

	/** Lock conflict target before read when already in a TX (SqlAutocommit / explicit). */
	static void ensureConflictKeyLocked(SqlSession session, String table, byte[] conflictKey) {
		if (session.inTransaction()) {
			ensureRowLocked(session, table, conflictKey);
		}
	}

	static byte[] existingBytes(SqlSession session, String table, TableStore store, byte[] key) {
		if (key == null) {
			return null;
		}
		if (session.inTransaction()) {
			return baseBytes(session, table, store, key);
		}
		return store.getCommittedBytes(key);
	}

	static byte[] baseBytes(SqlSession session, String table, TableStore store, byte[] key) {
		final SqlTxBuffer.DirtyEntry dirty = session.requireTx().get(table, key);
		if (dirty != null) {
			if (dirty.op() == SqlTxBuffer.Op.DELETE) {
				return null;
			}
			return dirty.valueBytesOrNull();
		}
		return store.getCommittedBytes(key);
	}

	/**
	 * Acquire row lock before RMW read. Staging alone locks too late (TOCTOU lost-update
	 * under concurrent autocommit / TX UPDATE).
	 */
	static void ensureRowLocked(SqlSession session, String table, byte[] key) {
		final SqlTxBuffer tx = session.requireTx();
		final KeyWrapper kw = new KeyWrapper(key);
		if (!tx.holdsLock(table, kw)) {
			session.lockManager().lock(table, key);
			tx.rememberLock(table, kw);
		}
	}

	static void stage(SqlSession session, String table, SqlTxBuffer.Op op, TableStore.EncodedRow enc) {
		final SqlTxBuffer tx = session.requireTx();
		ensureRowLocked(session, table, enc.keyBytes());
		tx.put(table, new SqlTxBuffer.DirtyEntry(op, enc.keyBytes(), enc.valueBytes(), enc.shard()));
	}
}
