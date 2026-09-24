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
package org.genfork.grid.replication.transport;

/**
 * Wire opcodes for Netty replication (ORCHID + OpLog + repair).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public enum ReplicationMessageType {
	HELLO(0x10),
	OPLOG_PUSH(0x11),
	OPLOG_PULL(0x12),
	APPLY_ACK(0x13),
	APPLY_NACK(0x14),
	REPAIR_REQUEST(0x15),
	REPAIR_REPLY(0x16),
	/** Packed APPLY_ACK list (same fromNodeId, N domain/shard/seq). */
	APPLY_ACK_BATCH(0x17),
	/**
	 * Sealed shard artifact pack ({@code .gmap}/{@code .sbpt}/{@code .sbm}) for migrate/repair ship.
	 * Body: {@code ReplicationRpcCodec} sealed-shard-pack layout.
	 */
	SEALED_SHARD_PACK(0x18),
	/**
	 * Hold/Witness claim request after Active remote-DC silence.
	 * Body: {@link org.genfork.grid.replication.netty.ReplicationRpcCodec#encodeRegionClaimReq}.
	 */
	REGION_CLAIM_REQ(0x19),
	/**
	 * Claim ACK from Hold/Witness voter.
	 * Body: {@link org.genfork.grid.replication.netty.ReplicationRpcCodec#encodeRegionClaimAck}.
	 */
	REGION_CLAIM_ACK(0x1A),
	/**
	 * Distributed FOR UPDATE peer lock request.
	 * Body: {@link org.genfork.grid.replication.netty.ReplicationRpcCodec#encodeForUpdateLockReq}.
	 * Handler must run on logic VT (never Netty EL).
	 */
	FOR_UPDATE_LOCK_REQ(0x1B),
	/**
	 * Peer lock ACK / NACK for {@link #FOR_UPDATE_LOCK_REQ}.
	 * Body: {@link org.genfork.grid.replication.netty.ReplicationRpcCodec#encodeForUpdateLockAck}.
	 */
	FOR_UPDATE_LOCK_ACK(0x1C),
	/**
	 * Release peer FOR UPDATE locks for a TX.
	 * Body: {@link org.genfork.grid.replication.netty.ReplicationRpcCodec#encodeForUpdateLockRelease}.
	 */
	FOR_UPDATE_LOCK_RELEASE(0x1D),
	/**
	 * Distributed FOR UPDATE prepare-vote request (product path; logic VT handlers).
	 * Body: {@link org.genfork.grid.replication.netty.ReplicationRpcCodec#encodeForUpdatePrepareReq}.
	 */
	FOR_UPDATE_PREPARE_REQ(0x1E),
	/**
	 * Peer prepare ACK / NACK for {@link #FOR_UPDATE_PREPARE_REQ}.
	 * Body: {@link org.genfork.grid.replication.netty.ReplicationRpcCodec#encodeForUpdatePrepareAck}.
	 */
	FOR_UPDATE_PREPARE_ACK(0x1F),
	/**
	 * Coordinator COMMIT / ABORT decision after prepare votes.
	 * Body: {@link org.genfork.grid.replication.netty.ReplicationRpcCodec#encodeForUpdateCommitDec}.
	 */
	FOR_UPDATE_COMMIT_DEC(0x20),
	ORCHID_PHASE(0x30),
	ORCHID_PROPOSE(0x31),
	ORCHID_COMMIT(0x32),
	ORCHID_NACK(0x33),
	/**
	 * Packed ORCHID phase digests/ACKs: {@code int32 count} + {@code count} repeated
	 * single-phase payloads (same body layout as {@link #ORCHID_PHASE}).
	 */
	ORCHID_PHASE_BATCH(0x34);

	private final int opcode;

	ReplicationMessageType(int opcode) {
		this.opcode = opcode;
	}

	public int opcode() {
		return opcode;
	}

	public static ReplicationMessageType fromOpcode(int opcode) {
		for (ReplicationMessageType type : values()) {
			if (type.opcode == opcode) {
				return type;
			}
		}
		return null;
	}
}
