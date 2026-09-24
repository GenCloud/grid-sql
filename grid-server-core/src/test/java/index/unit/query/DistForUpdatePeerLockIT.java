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
package index.unit.query;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.tx.InProcessDistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.LockWaitTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3: distributed FOR UPDATE peer locks — cross-node wait via in-process agents.
 * <p>
 * Netty transport handler path (no live peer channel): {@code ForUpdateLockTransportHandlerTest}.
 * PREPARE/COMMIT peer votes are scaffold-only on {@link TxEnvelopeCoordinator} / {@link org.genfork.grid.sql.tx.SqlTxCommitter}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class DistForUpdatePeerLockIT {
	private static final String TABLE = "fu_peer";
	private static final long SHORT_WAIT_MS = 300L;
	private static final long JOIN_TIMEOUT_MS = 5_000L;

	private SqlEngine primary;
	private SqlEngine peer;
	private TxEnvelopeCoordinator envelope;

	@BeforeEach
	void setUp() {
		primary = new SqlEngine(new TableCatalog(), null, 4);
		peer = new SqlEngine(new TableCatalog(), null, 4);
		envelope = new TxEnvelopeCoordinator();
		primary.lockManager().setLockWaitTimeoutMs(SHORT_WAIT_MS);
		peer.lockManager().setLockWaitTimeoutMs(SHORT_WAIT_MS);

		for (SqlEngine eng : List.of(primary, peer)) {
			eng.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, state VARCHAR)");
			eng.execute("CREATE INDEX " + TABLE + "_state ON " + TABLE + " (state)");
			eng.execute("INSERT INTO " + TABLE + " VALUES (1, 'new'), (2, 'new')");
		}

		primary.setDistForUpdatePeerLockAgents(List.of(
				new InProcessDistForUpdatePeerLockAgent(peer.lockManager())));
	}

	@Test
	void openTxForUpdateLocksPeerAndBlocksPeerLocalAcquire() throws Exception {
		final SqlSession writer = primary.newSession();
		writer.setEnvelopeCoordinator(envelope);
		primary.execute(writer, "BEGIN");
		final long txId = writer.requireTx().txId();
		final SqlResult locked = primary.execute(writer,
				"SELECT id FROM " + TABLE + " WHERE id = 1 FOR UPDATE");
		assertEquals(1, locked.rows().size());
		assertTrue(envelope.hasOpenLockPhase(txId));

		final AtomicReference<Throwable> error = new AtomicReference<>();
		final Thread waiter = Thread.ofVirtual().start(() -> {
			try {
				peer.lockManager().lock(TABLE, intKey(1));
				peer.lockManager().unlock(TABLE, intKey(1));
			} catch (Throwable t) {
				error.set(t);
			}
		});
		waiter.join(JOIN_TIMEOUT_MS);
		assertInstanceOf(LockWaitTimeoutException.class, error.get());

		primary.execute(writer, "ROLLBACK");
		assertFalse(envelope.hasOpenLockPhase(txId));
		// After release, peer manager must accept the lock.
		peer.lockManager().lock(TABLE, intKey(1));
		peer.lockManager().unlock(TABLE, intKey(1));
	}

	@Test
	void peerSkipLockedSeesEmptyWhilePrimaryHolds() {
		final SqlSession writer = primary.newSession();
		writer.setEnvelopeCoordinator(envelope);
		primary.execute(writer, "BEGIN");
		primary.execute(writer, "SELECT id FROM " + TABLE + " WHERE state = 'new' FOR UPDATE");

		final SqlSession peerSession = peer.newSession();
		peer.execute(peerSession, "BEGIN");
		final SqlResult skipped = peer.execute(peerSession,
				"SELECT id FROM " + TABLE + " WHERE state = 'new' FOR UPDATE SKIP LOCKED");
		assertEquals(0, skipped.rows().size(), "peer SKIP LOCKED must see primary-held peer locks");
		peer.execute(peerSession, "ROLLBACK");
		primary.execute(writer, "ROLLBACK");
	}

	@Test
	void plainForUpdateOnPeerTimesOutWhilePrimaryHolds() throws Exception {
		final SqlSession writer = primary.newSession();
		writer.setEnvelopeCoordinator(envelope);
		primary.execute(writer, "BEGIN");
		primary.execute(writer, "SELECT id FROM " + TABLE + " WHERE id = 1 FOR UPDATE");

		final SqlSession peerSession = peer.newSession();
		peer.execute(peerSession, "BEGIN");
		final AtomicReference<Throwable> error = new AtomicReference<>();
		final Thread t = Thread.ofVirtual().start(() -> {
			try {
				peer.execute(peerSession, "SELECT id FROM " + TABLE + " WHERE id = 1 FOR UPDATE");
			} catch (Throwable ex) {
				error.set(ex);
			}
		});
		t.join(JOIN_TIMEOUT_MS);
		assertInstanceOf(LockWaitTimeoutException.class, error.get());
		peer.execute(peerSession, "ROLLBACK");
		primary.execute(writer, "ROLLBACK");
	}

	@Test
	void joinForUpdateLocksBothTablesOnPeer() throws Exception {
		for (SqlEngine eng : List.of(primary, peer)) {
			eng.execute("CREATE TABLE fu_left (id INT PRIMARY KEY, rid INT)");
			eng.execute("CREATE TABLE fu_right (id INT PRIMARY KEY, state VARCHAR)");
			eng.execute("INSERT INTO fu_left VALUES (1, 10)");
			eng.execute("INSERT INTO fu_right VALUES (10, 'new')");
		}
		primary.setDistForUpdatePeerLockAgents(List.of(
				new InProcessDistForUpdatePeerLockAgent(peer.lockManager())));

		final SqlSession writer = primary.newSession();
		writer.setEnvelopeCoordinator(envelope);
		primary.execute(writer, "BEGIN");
		final SqlResult locked = primary.execute(writer,
				"SELECT fu_left.id FROM fu_left INNER JOIN fu_right ON fu_left.rid = fu_right.id FOR UPDATE");
		assertEquals(1, locked.rows().size());

		final AtomicReference<Throwable> error = new AtomicReference<>();
		final Thread waiter = Thread.ofVirtual().start(() -> {
			try {
				peer.lockManager().lock("fu_right", intKey(10));
				peer.lockManager().unlock("fu_right", intKey(10));
			} catch (Throwable t) {
				error.set(t);
			}
		});
		waiter.join(JOIN_TIMEOUT_MS);
		assertInstanceOf(LockWaitTimeoutException.class, error.get());
		primary.execute(writer, "ROLLBACK");
	}

	/** INT PK wire key used by SqlWireUtil / RowEncoder for this table. */
	private static byte[] intKey(int id) {
		return SqlWireUtil.toGenericArray(id);
	}
}