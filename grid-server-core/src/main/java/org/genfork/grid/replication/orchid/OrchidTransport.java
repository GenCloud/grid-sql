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
package org.genfork.grid.replication.orchid;

import org.genfork.grid.replication.codec.ReplicationOp;

import java.util.Collection;
import java.util.List;

/**
 * Outbound ORCHID RPCs over Netty.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public interface OrchidTransport {
	void sendPhase(String toNodeId, OrchidPhaseMessage message);

	void broadcastPhase(OrchidPhaseMessage message);

	/**
	 * Encode phase once and write to each peer (burst propose / tick path).
	 * Default loops {@link #sendPhase}; Netty overrides with single encode + coalesce flush.
	 */
	default void sendPhaseToMany(Collection<String> toNodeIds, OrchidPhaseMessage message) {
		if (toNodeIds == null || toNodeIds.isEmpty() || message == null) {
			return;
		}
		for (String toNodeId : toNodeIds) {
			sendPhase(toNodeId, message);
		}
	}

	/**
	 * Pack digests into one {@code ORCHID_PHASE_BATCH} (or single {@code ORCHID_PHASE} when count=1).
	 * Semantics identical to N {@link OrchidPhaseMessage} deliveries.
	 */
	default void sendPhaseDigestsToMany(Collection<String> toNodeIds, OrchidPhaseBatchMessage message) {
		if (toNodeIds == null || toNodeIds.isEmpty() || message == null) {
			return;
		}
		final List<OrchidPhaseDigest> digests = message.digests();
		if (digests == null || digests.isEmpty()) {
			sendPhaseToMany(toNodeIds, new OrchidPhaseMessage(
					message.nodeId(), message.phase(), message.omega(), message.lastCommittedSeq(), 0L, 0L));
			return;
		}
		if (digests.size() == 1) {
			final OrchidPhaseDigest one = digests.getFirst();
			sendPhaseToMany(toNodeIds, new OrchidPhaseMessage(
					message.nodeId(), message.phase(), message.omega(), message.lastCommittedSeq(),
					one.proposeId(), one.digest()));
			return;
		}
		for (OrchidPhaseDigest digest : digests) {
			sendPhaseToMany(toNodeIds, new OrchidPhaseMessage(
					message.nodeId(), message.phase(), message.omega(), message.lastCommittedSeq(),
					digest.proposeId(), digest.digest()));
		}
	}

	/**
	 * Coalesce N phase messages to each peer (batch opcode when {@code messages.size() > 1}).
	 * Default loops {@link #sendPhase}; Netty packs into one frame per peer.
	 */
	default void sendPhaseMessagesToMany(Collection<String> toNodeIds, List<OrchidPhaseMessage> messages) {
		if (toNodeIds == null || toNodeIds.isEmpty() || messages == null || messages.isEmpty()) {
			return;
		}
		if (messages.size() == 1) {
			sendPhaseToMany(toNodeIds, messages.getFirst());
			return;
		}
		for (OrchidPhaseMessage message : messages) {
			sendPhaseToMany(toNodeIds, message);
		}
	}

	void sendPropose(String toNodeId, OrchidProposeMessage message);

	void broadcastPropose(OrchidProposeMessage message);

	void sendCommit(String toNodeId, OrchidCommitMessage message);

	void broadcastCommit(OrchidCommitMessage message);

	void sendNack(String toNodeId, OrchidNackMessage message);

	record OrchidPhaseMessage(String nodeId, double phase, double omega, long lastCommittedSeq, long proposeId, long digest) {
	}

	record OrchidPhaseDigest(long proposeId, long digest) {
	}

	record OrchidPhaseBatchMessage(String nodeId, double phase, double omega, long lastCommittedSeq,
	                               List<OrchidPhaseDigest> digests) {
	}

	record OrchidProposeMessage(String proposerId, long proposeId, long digest, long prevOpSeq, ReplicationOp op) {
	}

	record OrchidCommitMessage(String committerId, long proposeId, long digest, long prevOpSeq, long opSeq, ReplicationOp op) {
	}

	record OrchidNackMessage(String fromNodeId, long proposeId, String reason) {
	}
}
