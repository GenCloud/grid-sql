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
package org.genfork.grid.replication.join;

import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.tx.OpLogTxUnits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Learner-first join + watermark sparse catch-up.
 * Catch-up ships only complete TX units (fail-closed mid-unit).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class SparseCatchUp {
	private static final Logger log = LoggerFactory.getLogger(SparseCatchUp.class);

	private final ReplicationNodeState nodeState;
	private final OpLog opLog;
	private final OrchidNode orchidNode;
	private final Set<String> readyForVoter = ConcurrentHashMap.newKeySet();

	public SparseCatchUp(ReplicationNodeState nodeState, OpLog opLog, OrchidNode orchidNode) {
		this.nodeState = nodeState;
		this.opLog = opLog;
		this.orchidNode = orchidNode;
	}

	public void onLearnerJoin(String peerId) {
		if (orchidNode != null) {
			orchidNode.onPeerAvailable(peerId);
		}
		log.info("SparseCatchUp: peer {} join signal; localEpoch={}", peerId, nodeState.getSchemaEpoch());
	}

	public List<ReplicationOp> catchUpFromWatermark(String domainType, int shard, long peerWatermark) {
		final long from = Math.max(1L, peerWatermark + 1);
		final List<ReplicationOp> raw = opLog.readFrom(domainType, shard, from, Integer.MAX_VALUE);
		return OpLogTxUnits.trimToCompleteUnits(raw);
	}

	public void catchUpAndShip(String domainType, int shard, long peerWatermark,
	                           BiConsumer<String, List<ReplicationOp>> shipper, String peerId) {
		final List<ReplicationOp> ops = catchUpFromWatermark(domainType, shard, peerWatermark);
		if (!ops.isEmpty() && shipper != null) {
			shipper.accept(peerId, ops);
		}
	}

	/**
	 * Mark peer ready for digest/voter membership after catch-up (beyond log-only watermark).
	 * Consumed by {@link OrchidNode#markVoterEligible}.
	 */
	public void markReadyForVoterPromote(String peerId) {
		if (peerId == null) {
			return;
		}
		readyForVoter.add(peerId);
		if (orchidNode != null) {
			orchidNode.markVoterEligible(peerId);
		}
		log.info("SparseCatchUp: peer {} ready for voter promote", peerId);
	}

	public boolean isReadyForVoterPromote(String peerId) {
		return peerId != null && readyForVoter.contains(peerId);
	}

	public void forget(String peerId) {
		if (peerId == null) {
			return;
		}
		readyForVoter.remove(peerId);
	}
}
