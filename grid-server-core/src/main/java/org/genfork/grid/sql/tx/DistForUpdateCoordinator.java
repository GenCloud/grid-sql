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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.sql.SqlSession;

/**
 * Distributed {@code FOR UPDATE} lock coordination (Phase 3 v1 / 2PC-lite).
 * <p>
 * After local {@link LockAwareKeyCursor} selection, acquire the same wire keys on every
 * configured {@link DistForUpdatePeerLockAgent} (fail-closed). SKIP LOCKED drops keys that
 * peers cannot grant. Leases are remembered on the TX buffer or returned for statement-scoped
 * release. Lock-phase membership is registered on {@link TxEnvelopeCoordinator} when provided
 * (reuse — not a second XA).
 * <p>
 * Peer PREPARE vote / COMMIT decision RPC rounds are wired via
 * {@link DistForUpdatePrepareVotes} from {@link SqlTxCommitter} (Netty prepare + commit-dec
 * when {@code peerLockedLeases} is non-empty; in-process leases keep a local self-ACK scaffold).
 * Durable multi-stream ship/apply still uses existing envelope COMMIT markers.
 * Multi-table {@code FOR UPDATE} (INNER JOIN) locks each side via
 * {@link org.genfork.grid.sql.exec.SqlForUpdateJoinLockOps} calling this coordinator per table.
 * Netty {@code FOR_UPDATE_LOCK_*} inbound/outbound is wired via
 * {@link org.genfork.grid.replication.netty.NettyReplicationTransport} /
 * {@link NettyDistForUpdatePeerLockAgent}.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class DistForUpdateCoordinator {
	private DistForUpdateCoordinator() {
	}

	/**
	 * Result after peer lock fan-out: possibly filtered selected keys + peer leases.
	 */
	public record PeerLockBatch(List<byte[]> selectedKeys, List<DistForUpdatePeerLockLease> peerLeases) {
	}

	/**
	 * Acquire peer locks for already-selected local keys. Empty agents → no-op.
	 *
	 * @param envelope optional; when non-null registers lock-phase scaffolding for {@code txId}
	 */
	public static PeerLockBatch acquirePeerLocks(
			SqlSession session,
			String table,
			List<byte[]> selectedKeys,
			List<byte[]> statementLocks,
			boolean skipLocked,
			List<DistForUpdatePeerLockAgent> agents,
			TxEnvelopeCoordinator envelope
	) {
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(selectedKeys, "selectedKeys");
		if (agents == null || agents.isEmpty() || selectedKeys.isEmpty()) {
			return new PeerLockBatch(selectedKeys, List.of());
		}
		final long txId = session.inTransaction() ? session.requireTx().txId() : 0L;
		int expectedPeers = 0;
		for (DistForUpdatePeerLockAgent a : agents) {
			if (a != null) {
				expectedPeers++;
			}
		}
		if (envelope != null && session.inTransaction() && expectedPeers > 0) {
			envelope.registerLockPhase(txId, expectedPeers);
		}
		final List<byte[]> kept = new ArrayList<>(selectedKeys.size());
		final List<DistForUpdatePeerLockLease> leases = new ArrayList<>();
		try {
			for (byte[] key : selectedKeys) {
				if (key == null) {
					continue;
				}
				final List<DistForUpdatePeerLockLease> keyLeases = new ArrayList<>(agents.size());
				boolean granted = true;
				for (int i = 0; i < agents.size(); i++) {
					final DistForUpdatePeerLockAgent agent = agents.get(i);
					if (agent == null) {
						continue;
					}
					final boolean locked = agent.lock(txId, table, key, skipLocked);
					if (!locked) {
						granted = false;
						break;
					}
					keyLeases.add(new DistForUpdatePeerLockLease(agent, txId, table, key));
				}
				if (!granted) {
					releaseAll(keyLeases);
					dropLocalLock(session, table, key, statementLocks);
					continue;
				}
				kept.add(key);
				leases.addAll(keyLeases);
			}
			if (!kept.isEmpty() && envelope != null && session.inTransaction()) {
				for (int i = 0; i < agents.size(); i++) {
					if (agents.get(i) != null) {
						envelope.notePeerLockAck(txId, i);
					}
				}
				if (!envelope.allPeerLocksAcked(txId)) {
					throw new IllegalStateException("dist FOR UPDATE lock-phase incomplete for txId=" + txId);
				}
			}
			if (session.inTransaction()) {
				final SqlTxBuffer buf = session.requireTx();
				for (DistForUpdatePeerLockLease lease : leases) {
					buf.rememberPeerLock(lease);
				}
				return new PeerLockBatch(List.copyOf(kept), List.of());
			}
			return new PeerLockBatch(List.copyOf(kept), List.copyOf(leases));
		} catch (RuntimeException ex) {
			releaseAll(leases);
			if (envelope != null && session.inTransaction()) {
				envelope.discardLockPhase(txId);
			}
			throw ex;
		}
	}

	/** Release statement-scoped peer leases (autocommit FOR UPDATE). */
	public static void releaseStatementPeerLocks(List<DistForUpdatePeerLockLease> leases) {
		releaseAll(leases);
	}

	/** Release TX-scoped peer leases and discard lock-phase scaffolding. */
	public static void releaseTxPeerLocks(SqlTxBuffer buf, TxEnvelopeCoordinator envelope) {
		if (buf == null) {
			return;
		}
		final long txId = buf.txId();
		for (DistForUpdatePeerLockLease lease : buf.peerLockedLeases()) {
			try {
				lease.release();
			} catch (RuntimeException ignored) {
			}
		}
		if (envelope != null) {
			envelope.discardLockPhase(txId);
			envelope.discardPreparePhase(txId);
		}
	}

	private static void dropLocalLock(
			SqlSession session,
			String table,
			byte[] key,
			List<byte[]> statementLocks
	) {
		session.lockManager().unlock(table, key);
		if (session.inTransaction()) {
			session.requireTx().forgetLock(table, new KeyWrapper(key));
		} else if (statementLocks != null) {
			final Iterator<byte[]> it = statementLocks.iterator();
			while (it.hasNext()) {
				final byte[] held = it.next();
				if (Arrays.equals(held, key)) {
					it.remove();
					break;
				}
			}
		}
	}

	private static void releaseAll(List<DistForUpdatePeerLockLease> leases) {
		if (leases == null || leases.isEmpty()) {
			return;
		}
		for (DistForUpdatePeerLockLease lease : leases) {
			try {
				lease.release();
			} catch (RuntimeException ignored) {
			}
		}
	}
}