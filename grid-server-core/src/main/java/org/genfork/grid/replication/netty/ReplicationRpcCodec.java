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
package org.genfork.grid.replication.netty;

import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.netty.codec.ApplyRpcCodec;
import org.genfork.grid.replication.netty.codec.HelloRpcCodec;
import org.genfork.grid.replication.netty.codec.PhaseRpcCodec;
import org.genfork.grid.replication.netty.codec.RegionClaimRpcCodec;
import org.genfork.grid.replication.netty.codec.RepairRpcCodec;
import org.genfork.grid.replication.netty.codec.SealedPackRpcCodec;
import org.genfork.grid.replication.netty.codec.TxRpcCodec;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidCommitMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidNackMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseBatchMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidProposeMessage;
import org.genfork.grid.replication.repair.RepairCommand;
import org.genfork.grid.replication.repair.VersionLocus;
import org.genfork.grid.replication.transport.ReplicationMessageType;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Binary codecs for HELLO / ACK / ORCHID / repair over Netty.
 * <p>
 * Façade: encode/decode bodies live in {@code org.genfork.grid.replication.netty.codec}
 * family helpers. Public method names and nested wire records are stable for callers.
 * <p>
 * {@link ReplicationMessageType#ORCHID_PHASE_BATCH} body = {@code int32 count} + {@code count}
 * repeated {@link #encodePhase} payloads (self-delimiting; same layout as single phase).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class ReplicationRpcCodec {
	private ReplicationRpcCodec() {
	}

	/**
	 * Peer handshake: identity + schema fence + Multi-DC {@code regionEpoch}/{@code regionRole}.
	 */
	public record Hello(
			String nodeId,
			String clusterId,
			String localDc,
			long schemaEpoch,
			long regionEpoch,
			byte regionRole
	) {
	}

	public record ApplyAck(String fromNodeId, String domainType, int shard, long opSeq) {
	}

	public record ApplyNack(String fromNodeId, String domainType, int shard, long opSeq, String reason) {
	}

	public record RepairRequest(String fromNodeId, String domainType, int shard, Map<Long, VersionLocus> view) {
	}

	public record RepairReply(String fromNodeId, List<RepairCommand> commands, List<ReplicationOp> ops) {
	}

	public record OplogPull(String fromNodeId, String domainType, int shard, long fromSeqInclusive) {
	}

	/**
	 * Sealed shard pack ship ({@link ReplicationMessageType#SEALED_SHARD_PACK}):
	 * fromNodeId, domainType, shard, packed blob ({@code SealedShardPack}).
	 */
	public record SealedShardPackMsg(String fromNodeId, String domainType, int shard, byte[] packed) {
	}

	/**
	 * {@link ReplicationMessageType#REGION_CLAIM_REQ}:
	 * claimId, proposedEpoch, fromNodeId, fromDc, claimantRoleWire.
	 */
	public record RegionClaimReq(
			long claimId,
			long proposedEpoch,
			String fromNodeId,
			String fromDc,
			byte claimantRoleWire
	) {
	}

	/**
	 * {@link ReplicationMessageType#REGION_CLAIM_ACK}:
	 * claimId, fromNodeId, voterRoleWire.
	 */
	public record RegionClaimAck(
			long claimId,
			String fromNodeId,
			byte voterRoleWire
	) {
	}

	/**
	 * {@link ReplicationMessageType#FOR_UPDATE_LOCK_REQ}: txId, skipLocked, table, keyBytes.
	 * <p>
	 * Wire scaffold for peer FOR UPDATE locks. Product handlers must park on logic VT only.
	 */
	public record ForUpdateLockReq(long txId, boolean skipLocked, String table, byte[] key) {
	}

	/**
	 * {@link ReplicationMessageType#FOR_UPDATE_LOCK_ACK}: txId, granted, fromNodeId.
	 */
	public record ForUpdateLockAck(long txId, boolean granted, String fromNodeId) {
	}

	/**
	 * {@link ReplicationMessageType#FOR_UPDATE_LOCK_RELEASE}: txId, table, keyBytes.
	 */
	public record ForUpdateLockRelease(long txId, String table, byte[] key) {
	}

	/**
	 * {@link ReplicationMessageType#FOR_UPDATE_PREPARE_REQ}: txId, fromNodeId (E2 scaffold).
	 */
	public record ForUpdatePrepareReq(long txId, String fromNodeId) {
	}

	/**
	 * {@link ReplicationMessageType#FOR_UPDATE_PREPARE_ACK}: txId, prepared, fromNodeId.
	 */
	public record ForUpdatePrepareAck(long txId, boolean prepared, String fromNodeId) {
	}

	/**
	 * {@link ReplicationMessageType#FOR_UPDATE_COMMIT_DEC}: txId, commit, fromNodeId.
	 */
	public record ForUpdateCommitDec(long txId, boolean commit, String fromNodeId) {
	}

	public static byte[] encodeHello(Hello hello) {
		return HelloRpcCodec.encodeHello(hello);
	}

	public static Hello decodeHello(byte[] body) {
		return HelloRpcCodec.decodeHello(body);
	}

	public static byte[] encodeApplyAck(ApplyAck ack) {
		return ApplyRpcCodec.encodeApplyAck(ack);
	}

	public static byte[] encodeApplyAckBatch(String fromNodeId, List<ApplyAck> acks) {
		return ApplyRpcCodec.encodeApplyAckBatch(fromNodeId, acks);
	}

	public static List<ApplyAck> decodeApplyAckBatch(byte[] body) {
		return ApplyRpcCodec.decodeApplyAckBatch(body);
	}

	public static ApplyAck decodeApplyAck(byte[] body) {
		return ApplyRpcCodec.decodeApplyAck(body);
	}

	public static byte[] encodeApplyNack(ApplyNack nack) {
		return ApplyRpcCodec.encodeApplyNack(nack);
	}

	public static ApplyNack decodeApplyNack(byte[] body) {
		return ApplyRpcCodec.decodeApplyNack(body);
	}

	public static byte[] encodePhase(OrchidPhaseMessage msg) {
		return PhaseRpcCodec.encodePhase(msg);
	}

	public static void encodePhaseInto(ByteBuffer buf, OrchidPhaseMessage msg) {
		PhaseRpcCodec.encodePhaseInto(buf, msg);
	}

	public static List<OrchidPhaseMessage> expandPhaseBatch(OrchidPhaseBatchMessage msg) {
		return PhaseRpcCodec.expandPhaseBatch(msg);
	}

	public static byte[] encodePhaseBatch(OrchidPhaseBatchMessage msg) {
		return PhaseRpcCodec.encodePhaseBatch(msg);
	}

	public static byte[] encodePhaseMessageBatch(List<OrchidPhaseMessage> messages) {
		return PhaseRpcCodec.encodePhaseMessageBatch(messages);
	}

	public static void encodePhaseMessageBatchInto(ByteBuffer buf, List<OrchidPhaseMessage> messages) {
		PhaseRpcCodec.encodePhaseMessageBatchInto(buf, messages);
	}

	public static List<OrchidPhaseMessage> decodePhaseMessageBatch(byte[] body) {
		return PhaseRpcCodec.decodePhaseMessageBatch(body);
	}

	public static OrchidPhaseMessage decodePhase(byte[] body) {
		return PhaseRpcCodec.decodePhase(body);
	}

	public static byte[] encodePropose(OrchidProposeMessage msg) {
		return PhaseRpcCodec.encodePropose(msg);
	}

	public static OrchidProposeMessage decodePropose(byte[] body) {
		return PhaseRpcCodec.decodePropose(body);
	}

	public static byte[] encodeCommit(OrchidCommitMessage msg) {
		return PhaseRpcCodec.encodeCommit(msg);
	}

	public static OrchidCommitMessage decodeCommit(byte[] body) {
		return PhaseRpcCodec.decodeCommit(body);
	}

	public static byte[] encodeOrchidNack(OrchidNackMessage msg) {
		return PhaseRpcCodec.encodeOrchidNack(msg);
	}

	public static OrchidNackMessage decodeOrchidNack(byte[] body) {
		return PhaseRpcCodec.decodeOrchidNack(body);
	}

	public static byte[] encodeRepairRequest(String fromNodeId, String domainType, int shard,
	                                         Map<Long, VersionLocus> view) {
		return RepairRpcCodec.encodeRepairRequest(fromNodeId, domainType, shard, view);
	}

	public static RepairRequest decodeRepairRequest(byte[] body) {
		return RepairRpcCodec.decodeRepairRequest(body);
	}

	public static byte[] encodeRepairReply(String fromNodeId, List<RepairCommand> commands, List<ReplicationOp> ops) {
		return RepairRpcCodec.encodeRepairReply(fromNodeId, commands, ops);
	}

	public static RepairReply decodeRepairReply(byte[] body) {
		return RepairRpcCodec.decodeRepairReply(body);
	}

	public static byte[] encodeOplogPull(String fromNodeId, String domainType, int shard, long fromSeqInclusive) {
		return RepairRpcCodec.encodeOplogPull(fromNodeId, domainType, shard, fromSeqInclusive);
	}

	public static OplogPull decodeOplogPull(byte[] body) {
		return RepairRpcCodec.decodeOplogPull(body);
	}

	public static byte[] encodeSealedShardPack(String fromNodeId, String domainType, int shard, byte[] packed) {
		return SealedPackRpcCodec.encodeSealedShardPack(fromNodeId, domainType, shard, packed);
	}

	public static SealedShardPackMsg decodeSealedShardPack(byte[] body) {
		return SealedPackRpcCodec.decodeSealedShardPack(body);
	}

	public static byte[] encodeRegionClaimReq(
			long claimId,
			long proposedEpoch,
			String fromNodeId,
			String fromDc,
			byte claimantRoleWire
	) {
		return RegionClaimRpcCodec.encodeRegionClaimReq(
				claimId, proposedEpoch, fromNodeId, fromDc, claimantRoleWire);
	}

	public static RegionClaimReq decodeRegionClaimReq(byte[] body) {
		return RegionClaimRpcCodec.decodeRegionClaimReq(body);
	}

	public static byte[] encodeRegionClaimAck(long claimId, String fromNodeId, byte voterRoleWire) {
		return RegionClaimRpcCodec.encodeRegionClaimAck(claimId, fromNodeId, voterRoleWire);
	}

	public static RegionClaimAck decodeRegionClaimAck(byte[] body) {
		return RegionClaimRpcCodec.decodeRegionClaimAck(body);
	}

	public static byte[] encodeForUpdateLockReq(
			long txId,
			boolean skipLocked,
			String table,
			byte[] key
	) {
		return TxRpcCodec.encodeForUpdateLockReq(txId, skipLocked, table, key);
	}

	public static ForUpdateLockReq decodeForUpdateLockReq(byte[] body) {
		return TxRpcCodec.decodeForUpdateLockReq(body);
	}

	public static byte[] encodeForUpdateLockAck(long txId, boolean granted, String fromNodeId) {
		return TxRpcCodec.encodeForUpdateLockAck(txId, granted, fromNodeId);
	}

	public static ForUpdateLockAck decodeForUpdateLockAck(byte[] body) {
		return TxRpcCodec.decodeForUpdateLockAck(body);
	}

	public static byte[] encodeForUpdateLockRelease(long txId, String table, byte[] key) {
		return TxRpcCodec.encodeForUpdateLockRelease(txId, table, key);
	}

	public static ForUpdateLockRelease decodeForUpdateLockRelease(byte[] body) {
		return TxRpcCodec.decodeForUpdateLockRelease(body);
	}

	public static byte[] encodeForUpdatePrepareReq(long txId, String fromNodeId) {
		return TxRpcCodec.encodeForUpdatePrepareReq(txId, fromNodeId);
	}

	public static ForUpdatePrepareReq decodeForUpdatePrepareReq(byte[] body) {
		return TxRpcCodec.decodeForUpdatePrepareReq(body);
	}

	public static byte[] encodeForUpdatePrepareAck(long txId, boolean prepared, String fromNodeId) {
		return TxRpcCodec.encodeForUpdatePrepareAck(txId, prepared, fromNodeId);
	}

	public static ForUpdatePrepareAck decodeForUpdatePrepareAck(byte[] body) {
		return TxRpcCodec.decodeForUpdatePrepareAck(body);
	}

	public static byte[] encodeForUpdateCommitDec(long txId, boolean commit, String fromNodeId) {
		return TxRpcCodec.encodeForUpdateCommitDec(txId, commit, fromNodeId);
	}

	public static ForUpdateCommitDec decodeForUpdateCommitDec(byte[] body) {
		return TxRpcCodec.decodeForUpdateCommitDec(body);
	}
}
