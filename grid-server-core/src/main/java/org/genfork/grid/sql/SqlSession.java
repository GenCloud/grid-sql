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
package org.genfork.grid.sql;

import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.sql.client.SessionRole;
import org.genfork.grid.sql.tx.DistForUpdateCoordinator;
import org.genfork.grid.sql.tx.DistForUpdatePeerLockLease;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import org.genfork.grid.sql.tx.SqlTxBuffer;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-connection session: auth, open SQL TX ({@link SqlTxBuffer}), prepared statements.
 * Record locks come from the owning {@link SqlEngine} (shared across sessions).
 * Prepared statements are capped by an LRU pool ({@code preparePoolSize}).
 * <p>
 * PREPARE stores the body SQL plus an ANTLR-parsed {@link Stmt}; EXECUTE rebinds
 * placeholders without re-parsing the body.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlSession {
	public static final int DEFAULT_PREPARE_POOL_SIZE = 64;

	private static final AtomicLong PREPARE_HANDLES = new AtomicLong(1L);

	/**
	 * Prepared body: SQL text + Stmt parsed at PREPARE (ANTLR once).
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public static final class PreparedEntry {
		private final String bodySql;
		private final Stmt bodyStmt;

		public PreparedEntry(String bodySql, Stmt bodyStmt) {
			this.bodySql = Objects.requireNonNull(bodySql, "bodySql");
			this.bodyStmt = Objects.requireNonNull(bodyStmt, "bodyStmt");
		}

		public String bodySql() {
			return bodySql;
		}

		public Stmt bodyStmt() {
			return bodyStmt;
		}
	}

	private final String user;
	private volatile boolean authenticated;
	private volatile SqlTxBuffer openTx;
	private volatile long prepareHandle;
	private final int preparePoolMax;
	private final Map<String, PreparedEntry> prepared;
	private final SqlRecordLockManager lockManager;
	private volatile String currentSchema = "public";
	private volatile ZoneId timezone = ZoneOffset.UTC;
	private volatile boolean remoteDirtyEnabled;
	private volatile SessionRole sessionRole = SessionRole.PRIMARY;
	private volatile TxEnvelopeCoordinator envelopeCoordinator;
	private final Map<String, Long> sequenceCurrval = new ConcurrentHashMap<>();
	private final AtomicInteger triggerNestingDepth = new AtomicInteger(0);

	public SqlSession(SqlRecordLockManager lockManager) {
		this("anonymous", true, lockManager, DEFAULT_PREPARE_POOL_SIZE);
	}

	public SqlSession(String user, boolean authenticated, SqlRecordLockManager lockManager) {
		this(user, authenticated, lockManager, DEFAULT_PREPARE_POOL_SIZE);
	}

	public SqlSession(String user, boolean authenticated, SqlRecordLockManager lockManager, int preparePoolSize) {
		this.user = user == null ? "anonymous" : user;
		this.authenticated = authenticated;
		this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
		this.preparePoolMax = preparePoolSize <= 0 ? Integer.MAX_VALUE : preparePoolSize;
		this.prepared = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, PreparedEntry> eldest) {
				return size() > preparePoolMax;
			}
		});
	}

	public String currentSchema() {
		return currentSchema;
	}

	public void setCurrentSchema(String schema) {
		if (schema == null || schema.isBlank()) {
			throw new IllegalArgumentException("schema required");
		}
		this.currentSchema = schema.trim().toLowerCase();
	}

	/** Session timezone for zone-less TIMESTAMPTZ / temporal bind coercion. */
	public ZoneId timezone() {
		return timezone;
	}

	public void setTimezone(ZoneId zone) {
		this.timezone = zone == null ? ZoneOffset.UTC : zone;
	}

	/** Whether this session may include explicitly supplied remote dirty upserts in fan-out. */
	public boolean remoteDirtyEnabled() {
		return remoteDirtyEnabled;
	}

	/** Opt-in only; defaults false and is reset only when the session closes. */
	public void setRemoteDirtyEnabled(boolean enabled) {
		this.remoteDirtyEnabled = enabled;
	}

	/** SESSION_OPEN role; PROMOTE_NOTIFY must not change this. */
	public SessionRole sessionRole() {
		return sessionRole;
	}

	public void setSessionRole(SessionRole role) {
		this.sessionRole = role == null ? SessionRole.PRIMARY : role;
	}

	public String user() {
		return user;
	}

	public boolean isAuthenticated() {
		return authenticated;
	}

	public void setAuthenticated(boolean authenticated) {
		this.authenticated = authenticated;
	}

	public boolean inTransaction() {
		return openTx != null;
	}

	public SqlTxBuffer openTxOrNull() {
		return openTx;
	}

	public SqlTxBuffer beginTx() {
		if (openTx != null) {
			throw new IllegalStateException("transaction already open");
		}
		prepareHandle = PREPARE_HANDLES.getAndIncrement();
		openTx = new SqlTxBuffer(prepareHandle);
		return openTx;
	}

	/**
	 * Opaque prepare/commit handle for this open TX (Spanner-style lite identity).
	 * Isolation remains record locks + OpLog {@code TX_*} markers — not channel serialization.
	 */
	public long prepareHandle() {
		return prepareHandle;
	}

	public SqlTxBuffer requireTx() {
		if (openTx == null) {
			throw new IllegalStateException("no transaction in progress");
		}
		return openTx;
	}

	public void savepoint(String name) {
		requireTx().savepoint(name);
	}

	public void rollbackToSavepoint(String name) {
		final SqlTxBuffer.SavepointRollback rollback = requireTx().rollbackTo(name);
		for (SqlTxBuffer.LockedKey lockedKey : rollback.localLocks()) {
			lockManager.unlock(lockedKey.table(), lockedKey.key().key());
		}
		for (DistForUpdatePeerLockLease peerLock : rollback.peerLocks()) {
			peerLock.release();
		}
	}

	public void releaseSavepoint(String name) {
		requireTx().releaseSavepoint(name);
	}

	public void endTx() {
		final SqlTxBuffer buf = openTx;
		openTx = null;
		prepareHandle = 0L;
		if (buf != null) {
			// Peer leases before local unlock so remote waiters unpark promptly.
			DistForUpdateCoordinator.releaseTxPeerLocks(buf, envelopeCoordinator);
			for (SqlTxBuffer.LockedKey lk : buf.lockedKeys()) {
				lockManager.unlock(lk.table(), lk.key().key());
			}
			buf.clear();
		}
	}

	/**
	 * Optional multi-stream / FOR UPDATE lock-phase coordinator (reuse — not a second XA).
	 */
	public void setEnvelopeCoordinator(TxEnvelopeCoordinator envelopeCoordinator) {
		this.envelopeCoordinator = envelopeCoordinator;
	}

	public TxEnvelopeCoordinator envelopeCoordinator() {
		return envelopeCoordinator;
	}

	public SqlRecordLockManager lockManager() {
		return lockManager;
	}

	/** Max prepared statements retained in this session (LRU). */
	public int preparePoolMax() {
		return preparePoolMax;
	}

	/** Current prepared-statement count (for tests / gauges). */
	public int preparedCount() {
		return prepared.size();
	}

	/**
	 * Store a PREPARE body. Prefer {@link #prepare(String, String, Stmt)} so EXECUTE can skip body ANTLR.
	 */
	public void prepare(String name, String sql) {
		prepare(name, sql, SqlStatementParser.parsePreparedBody(sql.trim(), timezone));
	}

	/**
	 * Store PREPARE name → body SQL + ANTLR {@link Stmt} (parsed once at PREPARE).
	 */
	public void prepare(String name, String sql, Stmt bodyStmt) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("PREPARE name required");
		}
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException("PREPARE body required");
		}
		prepared.put(name, new PreparedEntry(sql.trim(), bodyStmt));
	}

	public PreparedEntry preparedEntry(String name) {
		final PreparedEntry entry = prepared.get(name);
		if (entry == null) {
			throw new IllegalArgumentException("unknown prepared statement: " + name);
		}
		return entry;
	}

	public String preparedSql(String name) {
		return preparedEntry(name).bodySql();
	}

	public Stmt preparedStmt(String name) {
		return preparedEntry(name).bodyStmt();
	}

	public boolean hasPrepared(String name) {
		return prepared.containsKey(name);
	}

	public void deallocate(String name) {
		prepared.remove(name);
	}

	public Map<String, String> preparedSnapshot() {
		synchronized (prepared) {
			final Map<String, String> out = new LinkedHashMap<>();
			for (Map.Entry<String, PreparedEntry> e : prepared.entrySet()) {
				out.put(e.getKey(), e.getValue().bodySql());
			}
			return out;
		}
	}

	public void invalidatePrepared() {
		prepared.clear();
	}

	/** Record last {@code nextval} for session-scoped {@code currval}. */
	public void rememberSequenceValue(String sequenceName, long value) {
		if (sequenceName == null || sequenceName.isBlank()) {
			throw new IllegalArgumentException("sequence name required");
		}
		sequenceCurrval.put(sequenceName.toLowerCase(Locale.ROOT), value);
	}

	/** Session {@code currval}; fails if {@code nextval} was never called in this session. */
	public long currval(String sequenceName) {
		if (sequenceName == null || sequenceName.isBlank()) {
			throw new IllegalArgumentException("sequence name required");
		}
		final Long v = sequenceCurrval.get(sequenceName.toLowerCase(Locale.ROOT));
		if (v == null) {
			throw new IllegalStateException(
					"currval of sequence \"" + sequenceName + "\" is not yet defined in this session");
		}
		return v;
	}

	/**
	 * Enter nested trigger body execution; returns depth after increment (1-based).
	 */
	public int enterTriggerNesting() {
		return triggerNestingDepth.incrementAndGet();
	}

	/** Leave nested trigger body execution. */
	public void exitTriggerNesting() {
		triggerNestingDepth.decrementAndGet();
	}

	public int triggerNestingDepth() {
		return triggerNestingDepth.get();
	}
}
