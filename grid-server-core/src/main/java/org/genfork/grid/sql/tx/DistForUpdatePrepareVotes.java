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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.sql.SqlSession;

/**
 * Peer PREPARE / COMMIT_DEC fan-out for distributed {@code FOR UPDATE} (2PC-lite).
 * <p>
 * Collects unique {@link NettyDistForUpdatePeerLockAgent} peers from held leases, runs
 * prepare votes with a per-peer key-set via {@link NettyReplicationTransport}, then records
 * the commit decision. In-process-only leases keep a local self-ACK prepare (no Netty).
 * Not ORCHID tip / Kuramoto — SQL TX Dist path only.
 * <p>
 * Crash orphans on peers are cleaned by {@link ForUpdatePeerHeldKeys} lease TTL (no sidecar
 * prepare journal — OpLog TX units remain the durability path for mutations).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class DistForUpdatePrepareVotes {
	private static final int SELF_PEER_INDEX = 0;
	private static final int PEER_INDEX_BASE = 1;

	private DistForUpdatePrepareVotes() {
	}

	/**
	 * Before local map visibility: prepare phase. Fail-closed on peer NACK / timeout.
	 */
	public static void prepareOrThrow(SqlSession session, SqlTxBuffer buf, NettyReplicationTransport transport) {
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(buf, "buf");
		final List<DistForUpdatePeerLockLease> leases = buf.peerLockedLeases();
		if (leases.isEmpty()) {
			return;
		}
		final TxEnvelopeCoordinator envelope = session.envelopeCoordinator();
		if (envelope == null) {
			throw new IllegalStateException(
					"FOR UPDATE peer locks require TxEnvelopeCoordinator txId=" + buf.txId());
		}
		final long txId = buf.txId();
		final Map<String, List<ForUpdatePrepareWireUtil.TableKey>> keysByPeer = keysByNettyPeer(leases);
		final Set<String> nettyPeers = keysByPeer.keySet();
		final int expected = 1 + nettyPeers.size();
		envelope.registerPreparePhase(txId, expected);
		envelope.notePeerPrepareAck(txId, SELF_PEER_INDEX);
		if (transport == null || nettyPeers.isEmpty()) {
			if (!envelope.allPeersPrepared(txId)) {
				envelope.discardPreparePhase(txId);
				throw new IllegalStateException("FOR UPDATE prepare self-ACK incomplete txId=" + txId);
			}
			return;
		}
		int peerIndex = PEER_INDEX_BASE;
		for (Map.Entry<String, List<ForUpdatePrepareWireUtil.TableKey>> entry : keysByPeer.entrySet()) {
			final String peerId = entry.getKey();
			final boolean prepared = transport.sendForUpdatePrepareReq(peerId, txId, entry.getValue());
			if (!prepared) {
				envelope.discardPreparePhase(txId);
				transport.broadcastForUpdateCommitDec(txId, false);
				throw new IllegalStateException(
						"FOR UPDATE prepare NACK/timeout peerId=" + peerId + " txId=" + txId);
			}
			envelope.notePeerPrepareAck(txId, peerIndex++);
		}
		if (!envelope.allPeersPrepared(txId)) {
			envelope.discardPreparePhase(txId);
			transport.broadcastForUpdateCommitDec(txId, false);
			throw new IllegalStateException("FOR UPDATE prepare quorum incomplete txId=" + txId);
		}
	}

	/**
	 * After successful or failed commit path: record decision, notify Netty peers, drop phase.
	 */
	public static void finish(
			SqlSession session,
			SqlTxBuffer buf,
			NettyReplicationTransport transport,
			boolean commit
	) {
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(buf, "buf");
		final List<DistForUpdatePeerLockLease> leases = buf.peerLockedLeases();
		if (leases.isEmpty()) {
			return;
		}
		final long txId = buf.txId();
		final TxEnvelopeCoordinator envelope = session.envelopeCoordinator();
		if (envelope != null) {
			envelope.recordCommitDecision(txId, commit);
			envelope.discardPreparePhase(txId);
		}
		final Map<String, List<ForUpdatePrepareWireUtil.TableKey>> keysByPeer = keysByNettyPeer(leases);
		if (transport != null && !keysByPeer.isEmpty()) {
			transport.broadcastForUpdateCommitDec(txId, commit);
		}
		if (!commit) {
			for (DistForUpdatePeerLockLease lease : leases) {
				try {
					lease.releaseAwait();
				} catch (RuntimeException ignored) {
					// release miss recorded by transport metric; TTL sweeper cleans orphans
				}
			}
		}
	}

	private static Map<String, List<ForUpdatePrepareWireUtil.TableKey>> keysByNettyPeer(
			List<DistForUpdatePeerLockLease> leases
	) {
		final LinkedHashMap<String, List<ForUpdatePrepareWireUtil.TableKey>> byPeer = new LinkedHashMap<>();
		for (DistForUpdatePeerLockLease lease : leases) {
			final DistForUpdatePeerLockAgent agent = lease.agent();
			if (agent instanceof NettyDistForUpdatePeerLockAgent netty) {
				byPeer.computeIfAbsent(netty.peerId(), ignored -> new ArrayList<>())
						.add(new ForUpdatePrepareWireUtil.TableKey(lease.table(), lease.key()));
			}
		}
		return byPeer;
	}
}
