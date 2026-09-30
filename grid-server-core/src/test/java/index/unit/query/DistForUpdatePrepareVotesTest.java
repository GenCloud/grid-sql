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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.tx.DistForUpdatePeerLockLease;
import org.genfork.grid.sql.tx.DistForUpdatePrepareVotes;
import org.genfork.grid.sql.tx.InProcessDistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.NettyDistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.SqlTxBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit: {@link DistForUpdatePrepareVotes} fail-closed prepare and self-ACK scaffold.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public class DistForUpdatePrepareVotesTest {
	private static final String TABLE = "fu_prep_votes";
	private static final byte[] KEY = new byte[]{7, 7};
	private static final String PEER_ID = "peer-b";

	private SqlEngine engine;
	private TxEnvelopeCoordinator envelope;
	private NettyReplicationTransport transport;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		envelope = new TxEnvelopeCoordinator();
		transport = new NettyReplicationTransport(
				"coord",
				"cluster",
				"dc0",
				1L,
				"127.0.0.1",
				0,
				1_000L,
				1 << 20,
				List.of());
	}

	@AfterEach
	void tearDown() {
		transport.close();
	}

	@Test
	void emptyLeasesNoOp() {
		final SqlSession session = openTx();
		final SqlTxBuffer buf = session.requireTx();
		assertDoesNotThrow(() -> DistForUpdatePrepareVotes.prepareOrThrow(session, buf, transport));
		assertDoesNotThrow(() -> DistForUpdatePrepareVotes.finish(session, buf, transport, true));
	}

	@Test
	void inProcessLeasesSelfAckPrepare() {
		final SqlSession session = openTx();
		final SqlTxBuffer buf = session.requireTx();
		final InProcessDistForUpdatePeerLockAgent agent =
				new InProcessDistForUpdatePeerLockAgent(engine.lockManager());
		buf.rememberPeerLock(new DistForUpdatePeerLockLease(agent, buf.txId(), TABLE, KEY));
		assertDoesNotThrow(() -> DistForUpdatePrepareVotes.prepareOrThrow(session, buf, transport));
		assertDoesNotThrow(() -> DistForUpdatePrepareVotes.finish(session, buf, transport, true));
	}

	@Test
	void nettyPeerWithoutChannelFailsClosed() {
		final SqlSession session = openTx();
		final SqlTxBuffer buf = session.requireTx();
		final NettyDistForUpdatePeerLockAgent agent =
				new NettyDistForUpdatePeerLockAgent(transport, PEER_ID);
		buf.rememberPeerLock(new DistForUpdatePeerLockLease(agent, buf.txId(), TABLE, KEY));
		final IllegalStateException ex = assertThrows(
				IllegalStateException.class,
				() -> DistForUpdatePrepareVotes.prepareOrThrow(session, buf, transport));
		assertTrue(ex.getMessage().contains("FOR UPDATE prepare"));
	}

	@Test
	void nettyPeerWithoutEnvelopeFailsClosed() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		final SqlTxBuffer buf = session.requireTx();
		final NettyDistForUpdatePeerLockAgent agent =
				new NettyDistForUpdatePeerLockAgent(transport, PEER_ID);
		buf.rememberPeerLock(new DistForUpdatePeerLockLease(agent, buf.txId(), TABLE, KEY));
		final IllegalStateException ex = assertThrows(
				IllegalStateException.class,
				() -> DistForUpdatePrepareVotes.prepareOrThrow(session, buf, transport));
		assertTrue(ex.getMessage().contains("TxEnvelopeCoordinator"));
	}

	private SqlSession openTx() {
		final SqlSession session = engine.newSession();
		session.setEnvelopeCoordinator(envelope);
		engine.execute(session, "BEGIN");
		return session;
	}
}
