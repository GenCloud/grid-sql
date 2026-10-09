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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.genfork.grid.query.distributed.DistributedKeyFanOut;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.tx.KeyWrapper;
import org.genfork.grid.sql.tx.SqlTxBuffer;
import org.genfork.grid.store.TableStore;

/**
 * EXISTS early-stop on index / PK / TX overlay — no synthetic SELECT re-execute.
 * <p>
 * Uses the caller subquery's user SQL string with {@link TableStore#forEachSelectKeys}
 * (stop on first visible key). Does not rewrite projection / LIMIT into a new statement.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class SqlExistsProbeUtil {
	/** Peer fan-out key cap for EXISTS (one hit is enough). */
	private static final int EXISTS_PEER_KEY_LIMIT = 1;

	private SqlExistsProbeUtil() {
	}

	/**
	 * {@code true} when at least one visible row matches the user subquery plan.
	 *
	 * @param residualFilter filter from the subquery SQL (dirty insert overlay); never null
	 * @param peerKeyExecutors nullable committed peer key suppliers
	 */
	public static boolean probe(
			SqlSession session,
			String table,
			TableStore store,
			SelectSql subquery,
			FilterCondition residualFilter,
			List<Function<String, List<byte[]>>> peerKeyExecutors
	) {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(subquery, "subquery");
		Objects.requireNonNull(table, "table");
		final FilterCondition residual = residualFilter == null
				? AlwaysTrueCondition.getInstance()
				: residualFilter;
		final String selectSql = subquery.sql();
		if (selectSql == null || selectSql.isBlank()) {
			throw new IllegalArgumentException("EXISTS subquery SQL is empty");
		}
		if (SqlPkLookupUtil.isScalarPkPointLookup(store.schema(), subquery.pkColumnOrNull())) {
			return probePkPoint(session, table, store, subquery);
		}
		if (session != null && session.inTransaction()) {
			return probeOpenTx(session, table, store, selectSql, residual);
		}
		if (probeCommittedEarlyStop(store, selectSql)) {
			return true;
		}
		if (peerKeyExecutors == null || peerKeyExecutors.isEmpty()) {
			return false;
		}
		final List<byte[]> keys = DistributedKeyFanOut.fanOutKeys(
				selectSql, EXISTS_PEER_KEY_LIMIT, true, store, peerKeyExecutors);
		return keys != null && !keys.isEmpty();
	}

	private static boolean probePkPoint(
			SqlSession session,
			String table,
			TableStore store,
			SelectSql subquery
	) {
		final byte[] key = store.keyBytesForPk(subquery.pkValueOrNull());
		if (session != null && session.inTransaction()) {
			final SqlTxBuffer.DirtyEntry dirty = session.requireTx().get(table, key);
			if (dirty != null) {
				return dirty.op() != SqlTxBuffer.Op.DELETE && dirty.valueBytesOrNull() != null;
			}
		}
		return store.getCommittedBytes(key) != null;
	}

	private static boolean probeCommittedEarlyStop(TableStore store, String selectSql) {
		final ProbeHit hit = new ProbeHit();
		store.forEachSelectKeys(selectSql, key -> {
			if (store.getCommittedBytes(key) != null) {
				hit.found = true;
				return false;
			}
			return true;
		});
		return hit.found;
	}

	private static boolean probeOpenTx(
			SqlSession session,
			String table,
			TableStore store,
			String selectSql,
			FilterCondition residual
	) {
		final SqlTxBuffer tx = session.requireTx();
		final ProbeHit hit = new ProbeHit();
		store.forEachSelectKeys(selectSql, key -> {
			final SqlTxBuffer.DirtyEntry dirty = tx.get(table, key);
			if (dirty != null) {
				if (dirty.op() == SqlTxBuffer.Op.DELETE) {
					return true;
				}
				if (dirty.valueBytesOrNull() != null) {
					hit.found = true;
					return false;
				}
				return true;
			}
			if (store.getCommittedBytes(key) != null) {
				hit.found = true;
				return false;
			}
			return true;
		});
		if (hit.found) {
			return true;
		}
		for (Map.Entry<KeyWrapper, SqlTxBuffer.DirtyEntry> e : tx.entriesForTable(table).entrySet()) {
			final SqlTxBuffer.DirtyEntry dirty = e.getValue();
			if (dirty.op() != SqlTxBuffer.Op.UPSERT) {
				continue;
			}
			final byte[] valueBytes = dirty.valueBytesOrNull();
			if (valueBytes != null && residual.matches(valueBytes, store.schema())) {
				return true;
			}
		}
		return false;
	}

	/** Mutable early-stop flag (avoids AtomicBoolean alloc on hot EXISTS). */
	private static final class ProbeHit {
		boolean found;
	}
}
