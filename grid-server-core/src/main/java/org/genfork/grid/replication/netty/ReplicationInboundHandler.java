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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.genfork.grid.codec.duplex.DuplexBlob;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.join.SparseCatchUp;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.netty.ReplicationFrameCodec.WireMessage;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ApplyAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ApplyNack;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.Hello;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.OplogPull;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.RepairReply;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.RepairRequest;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseMessage;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.repair.RepairCommand;
import org.genfork.grid.replication.repair.RepairCommandType;
import org.genfork.grid.replication.transport.DiscoveredPeer;
import org.genfork.grid.replication.transport.ReplicationMessageType;
import org.genfork.grid.replication.tx.OpLogTxUnits;
import org.genfork.grid.threading.ThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Demux inbound replication frames: OpLog / ORCHID / repair / ACK / sealed pack.
 * <p>
 * {@link ReplicationMessageType#ORCHID_PHASE_BATCH} unpacks to N {@link OrchidNode#onPhase} calls
 * (same digest quorum semantics as single phase frames). Phase / propose / commit bodies run off
 * the event loop via OrchidNode logic mailboxes.
 * {@link ReplicationMessageType#SEALED_SHARD_PACK} is applied off the event loop.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicationInboundHandler extends SimpleChannelInboundHandler<WireMessage> {
	private static final Logger log = LoggerFactory.getLogger(ReplicationInboundHandler.class);

	public static final AttributeKey<String> REMOTE_NODE_ATTR = AttributeKey.valueOf("replication.remoteNodeId");

	private final String localNodeId;
	private final String clusterId;
	private final String localDc;
	private final AtomicLong schemaEpoch;
	private final Supplier<OrchidNode> orchidNode;
	private final Function<String, ReplicaApplier> applierByDomain;
	private final CrossDcPublisher crossDcPublisher;
	private final ReplicationNodeState nodeState;
	private final HomologousRepair homologousRepair;
	private final Map<String, Channel> remoteChannels;
	private final NettyReplicationTransport transport;
	private final OpLog opLog;
	private final SparseCatchUp sparseCatchUp;
	private final Consumer<DiscoveredPeer> onPeerHello;
	private final Consumer<String> onApplyAckPeer;

	public ReplicationInboundHandler(String localNodeId,
	                                 String clusterId,
	                                 String localDc,
	                                 AtomicLong schemaEpoch,
	                                 Supplier<OrchidNode> orchidNode,
	                                 Function<String, ReplicaApplier> applierByDomain,
	                                 CrossDcPublisher crossDcPublisher,
	                                 ReplicationNodeState nodeState,
	                                 HomologousRepair homologousRepair,
	                                 Map<String, Channel> remoteChannels,
	                                 NettyReplicationTransport transport,
	                                 OpLog opLog,
	                                 SparseCatchUp sparseCatchUp,
	                                 Consumer<DiscoveredPeer> onPeerHello,
	                                 Consumer<String> onApplyAckPeer) {
		this.localNodeId = localNodeId;
		this.clusterId = clusterId;
		this.localDc = localDc;
		this.schemaEpoch = schemaEpoch;
		this.orchidNode = orchidNode;
		this.applierByDomain = applierByDomain;
		this.crossDcPublisher = crossDcPublisher;
		this.nodeState = nodeState;
		this.homologousRepair = homologousRepair;
		this.remoteChannels = remoteChannels;
		this.transport = transport;
		this.opLog = opLog;
		this.sparseCatchUp = sparseCatchUp;
		this.onPeerHello = onPeerHello == null ? p -> {} : onPeerHello;
		this.onApplyAckPeer = onApplyAckPeer == null ? id -> {} : onApplyAckPeer;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		final Hello hello = new Hello(
				localNodeId,
				clusterId,
				localDc,
				schemaEpoch.get(),
				transport.currentRegionEpoch(),
				transport.currentRegionRoleWire());
		ctx.writeAndFlush(new WireMessage(ReplicationMessageType.HELLO.opcode(), ReplicationRpcCodec.encodeHello(hello)));
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, WireMessage msg) {
		final ReplicationMessageType type = ReplicationMessageType.fromOpcode(msg.opcode());
		if (type == null) {
			log.warn("Unknown replication opcode={}", msg.opcode());
			return;
		}
		final OrchidNode node = orchidNode.get();
		switch (type) {
			case HELLO -> onHello(ctx, msg.body());
			case OPLOG_PUSH -> onOplogPush(msg.body());
			case OPLOG_PULL -> onOplogPull(msg.body());
			case APPLY_ACK -> onApplyAck(msg.body());
			case APPLY_ACK_BATCH -> onApplyAckBatch(msg.body());
			case APPLY_NACK -> onApplyNack(msg.body());
			case ORCHID_PHASE -> {
				if (node != null) {
					node.onPhase(ReplicationRpcCodec.decodePhase(msg.body()));
				}
			}
			case ORCHID_PHASE_BATCH -> {
				if (node != null) {
					final List<OrchidPhaseMessage> phases =
							ReplicationRpcCodec.decodePhaseMessageBatch(msg.body());
					for (OrchidPhaseMessage phase : phases) {
						node.onPhase(phase);
					}
				}
			}
			case ORCHID_PROPOSE -> {
				if (node != null) {
					node.onPropose(ReplicationRpcCodec.decodePropose(msg.body()));
				}
			}
			case ORCHID_COMMIT -> {
				if (node != null) {
					// Decode on EL; OrchidNode serializes holdback/apply on logic VT mailbox.
					node.onCommit(ReplicationRpcCodec.decodeCommit(msg.body()));
				}
			}
			case ORCHID_NACK -> {
				if (node != null) {
					node.onNack(ReplicationRpcCodec.decodeOrchidNack(msg.body()));
				}
			}
			case REPAIR_REQUEST -> onRepairRequest(ctx, msg.body());
			case REPAIR_REPLY -> onRepairReply(msg.body());
			case SEALED_SHARD_PACK -> onSealedShardPack(msg.body());
			case REGION_CLAIM_REQ -> onRegionClaimReq(ctx, msg.body());
			case REGION_CLAIM_ACK -> onRegionClaimAck(msg.body());
			case FOR_UPDATE_LOCK_REQ -> onForUpdateLockReq(ctx, msg.body());
			case FOR_UPDATE_LOCK_ACK -> onForUpdateLockAck(msg.body());
			case FOR_UPDATE_LOCK_RELEASE -> onForUpdateLockRelease(msg.body());
			case FOR_UPDATE_PREPARE_REQ -> onForUpdatePrepareReq(ctx, msg.body());
			case FOR_UPDATE_PREPARE_ACK -> onForUpdatePrepareAck(msg.body());
			case FOR_UPDATE_COMMIT_DEC -> onForUpdateCommitDec(msg.body());
			default -> log.debug("Unhandled replication message {}", type);
		}
	}

	private void onSealedShardPack(byte[] body) {
		// Unpack / open sealed files off Netty EL (may touch disk / mmap).
		ThreadService.getLogicExecutor().execute(() -> transport.applySealedShardPack(body));
	}

	private void onRegionClaimReq(ChannelHandlerContext ctx, byte[] body) {
		ThreadService.getLogicExecutor().execute(() -> {
			final byte[] ackBody = transport.handleRegionClaimReq(body);
			if (ackBody != null && ctx.channel().isActive()) {
				ctx.writeAndFlush(new WireMessage(ReplicationMessageType.REGION_CLAIM_ACK.opcode(), ackBody));
			}
		});
	}

	private void onRegionClaimAck(byte[] body) {
		ThreadService.getLogicExecutor().execute(() -> transport.handleRegionClaimAck(body));
	}

	private void onForUpdateLockReq(ChannelHandlerContext ctx, byte[] body) {
		ThreadService.getLogicExecutor().execute(() -> {
			final byte[] ackBody = transport.handleForUpdateLockReq(body);
			if (ackBody != null && ctx.channel().isActive()) {
				ctx.writeAndFlush(new WireMessage(ReplicationMessageType.FOR_UPDATE_LOCK_ACK.opcode(), ackBody));
			}
		});
	}

	private void onForUpdateLockAck(byte[] body) {
		ThreadService.getLogicExecutor().execute(() -> transport.handleForUpdateLockAck(body));
	}

	private void onForUpdateLockRelease(byte[] body) {
		ThreadService.getLogicExecutor().execute(() -> transport.handleForUpdateLockRelease(body));
	}

	private void onForUpdatePrepareReq(ChannelHandlerContext ctx, byte[] body) {
		ThreadService.getLogicExecutor().execute(() -> {
			final byte[] ackBody = transport.handleForUpdatePrepareReq(body);
			if (ackBody != null && ctx.channel().isActive()) {
				ctx.writeAndFlush(new WireMessage(ReplicationMessageType.FOR_UPDATE_PREPARE_ACK.opcode(), ackBody));
			}
		});
	}

	private void onForUpdatePrepareAck(byte[] body) {
		ThreadService.getLogicExecutor().execute(() -> transport.handleForUpdatePrepareAck(body));
	}

	private void onForUpdateCommitDec(byte[] body) {
		ThreadService.getLogicExecutor().execute(() -> transport.handleForUpdateCommitDec(body));
	}

	private void onHello(ChannelHandlerContext ctx, byte[] body) {
		final Hello hello = ReplicationRpcCodec.decodeHello(body);
		if (!clusterId.equals(hello.clusterId())) {
			log.warn("Reject HELLO cluster mismatch remote={} expected={}", hello.clusterId(), clusterId);
			ctx.close();
			return;
		}
		if (schemaEpoch.get() != 0 && hello.schemaEpoch() != schemaEpoch.get()) {
			log.warn("Reject HELLO schemaEpoch mismatch remote={} local={}", hello.schemaEpoch(), schemaEpoch.get());
			ctx.close();
			return;
		}
		ctx.channel().attr(REMOTE_NODE_ATTR).set(hello.nodeId());
		remoteChannels.put(hello.nodeId(), ctx.channel());
		final OrchidNode node = orchidNode.get();
		if (node != null) {
			node.onPeerAvailable(hello.nodeId());
		}
		String host = "127.0.0.1";
		int port = 0;
		if (ctx.channel().remoteAddress() instanceof InetSocketAddress isa) {
			host = isa.getAddress() != null ? isa.getAddress().getHostAddress() : isa.getHostString();
			port = isa.getPort();
		}
		onPeerHello.accept(new DiscoveredPeer(
				hello.nodeId(), host, port, hello.localDc(), hello.regionEpoch(), hello.regionRole()));
		log.info("Replication HELLO from node={} dc={} regionEpoch={} regionRole={}",
				hello.nodeId(), hello.localDc(), hello.regionEpoch(), hello.regionRole());
	}

	private void onOplogPush(byte[] body) {
		ReplicationMetrics.recordOplogPushRecv();
		// Decode + apply off Netty EL (ReplicaApplier uses per-shard monitors / VT work).
		ThreadService.getLogicExecutor().execute(() ->
				transport.runWithApplyAckCoalesce(() -> applyOplogPush(body)));
	}

	private void applyOplogPush(byte[] body) {
		final OpLogSegment segment = OpLogCodec.decodeSegment(body);
		for (ReplicationOp op : segment.ops()) {
			ReplicationOp current = op;
			byte[] value = current.value();
			if (value != null && DuplexCodecSupport.isActiveForReplication() && DuplexBlob.isWire(value)) {
				value = homologousRepair != null
						? homologousRepair.verifyOrRebuildValue(value)
						: DuplexCodecSupport.getCodec().getVerifier()
						.verifyOrRepair(DuplexBlob.fromWireBytes(value)).toWireBytes();
				current = new ReplicationOp(current.domainType(), current.shard(), current.opSeq(), current.type(),
						current.key(), value, current.schemaEpoch(), current.checksum());
			}
			if (homologousRepair != null) {
				homologousRepair.observe(current);
			}
			final ReplicaApplier applier = applierByDomain.apply(current.domainType());
			if (applier != null) {
				applier.apply(current, false);
			}
		}
	}

	private void onOplogPull(byte[] body) {
		final OplogPull pull = ReplicationRpcCodec.decodeOplogPull(body);
		if (opLog == null || sparseCatchUp == null) {
			return;
		}
		final List<ReplicationOp> ops = sparseCatchUp.catchUpFromWatermark(
				pull.domainType(), pull.shard(), Math.max(0L, pull.fromSeqInclusive() - 1));
		if (ops.isEmpty()) {
			return;
		}
		final OpLogSegment segment = new OpLogSegment(
				pull.domainType(), pull.shard(),
				ops.getFirst().opSeq(), ops.getLast().opSeq(), ops,
				OpLogCodec.segmentChecksum(ops)
		);
		transport.pushSegment(pull.fromNodeId(), segment);
	}

	private void onApplyAck(byte[] body) {
		ReplicationMetrics.recordApplyAckRecv();
		final ApplyAck ack = ReplicationRpcCodec.decodeApplyAck(body);
		applyOneAck(ack);
	}

	private void onApplyAckBatch(byte[] body) {
		final List<ApplyAck> acks = ReplicationRpcCodec.decodeApplyAckBatch(body);
		for (ApplyAck ack : acks) {
			ReplicationMetrics.recordApplyAckRecv();
			applyOneAck(ack);
		}
	}

	private void applyOneAck(ApplyAck ack) {
		final long latencyMs = transport.notePeerAck(ack.fromNodeId());
		nodeState.advancePeerAck(ack.fromNodeId(), ack.domainType(), ack.shard(), ack.opSeq());
		if (crossDcPublisher != null) {
			crossDcPublisher.onRemoteAck(ack.domainType(), ack.shard(), ack.opSeq(), latencyMs);
		}
		onApplyAckPeer.accept(ack.fromNodeId());
	}

	private void onApplyNack(byte[] body) {
		ReplicationMetrics.recordApplyNackRecv();
		final ApplyNack nack = ReplicationRpcCodec.decodeApplyNack(body);
		log.warn("APPLY_NACK from={} domain={} shard={} seq={} reason={}",
				nack.fromNodeId(), nack.domainType(), nack.shard(), nack.opSeq(), nack.reason());
	}

	private void onRepairRequest(ChannelHandlerContext ctx, byte[] body) {
		if (homologousRepair == null) {
			return;
		}
		ThreadService.getLogicExecutor().execute(() -> {
			final RepairRequest req = ReplicationRpcCodec.decodeRepairRequest(body);
			final List<RepairCommand> commands = homologousRepair.reconcile(req.domainType(), req.shard(), req.view());
			if (commands.isEmpty()) {
				final byte[] emptyReply = ReplicationRpcCodec.encodeRepairReply(localNodeId, commands, List.of());
				ctx.executor().execute(() ->
						ctx.writeAndFlush(new WireMessage(ReplicationMessageType.REPAIR_REPLY.opcode(), emptyReply)));
				return;
			}
			final List<ReplicationOp> ops = new ArrayList<>();
			if (opLog != null) {
				for (RepairCommand cmd : commands) {
					if (cmd.type() == RepairCommandType.RESHIP_SEGMENT || cmd.type() == RepairCommandType.FETCH_OP) {
						final long from = Math.max(1L, Math.min(cmd.localOpSeq(),
								cmd.remoteOpSeq() == 0 ? cmd.localOpSeq() : cmd.remoteOpSeq()));
						final long to = Math.max(cmd.localOpSeq(), cmd.remoteOpSeq());
						final List<ReplicationOp> range = opLog.readFrom(req.domainType(), req.shard(), from, 256);
						final List<ReplicationOp> complete = OpLogTxUnits.trimToCompleteUnits(range);
						for (ReplicationOp op : complete) {
							if (to == 0 || op.opSeq() <= to) {
								ops.add(op);
							}
						}
					} else if (cmd.type() == RepairCommandType.FETCH_ROW) {
						final long seq = cmd.remoteOpSeq() > 0 ? cmd.remoteOpSeq() : cmd.localOpSeq();
						final long windowFrom = Math.max(1L, seq - 256L);
						final List<ReplicationOp> window =
								opLog.readFrom(req.domainType(), req.shard(), windowFrom, 512);
						if (!OpLogTxUnits.isSeqInCompleteUnit(window, seq)) {
							continue;
						}
						final List<ReplicationOp> at = opLog.readFrom(req.domainType(), req.shard(), seq, 1);
						for (ReplicationOp op : at) {
							if (op.opSeq() == seq
									&& HomologousRepair.keyHash(op.key()) == cmd.keyHash()) {
								ops.add(op);
								break;
							}
						}
					}
				}
			}
			final byte[] reply = ReplicationRpcCodec.encodeRepairReply(localNodeId, commands, ops);
			ctx.executor().execute(() ->
					ctx.writeAndFlush(new WireMessage(ReplicationMessageType.REPAIR_REPLY.opcode(), reply)));
		});
	}

	private void onRepairReply(byte[] body) {
		// Decode on EL; skip logic enqueue for empty sync probes (O(streams) every reconcile tick).
		final RepairReply reply = ReplicationRpcCodec.decodeRepairReply(body);
		if (reply.ops().isEmpty()) {
			if (log.isDebugEnabled()) {
				log.debug("REPAIR_REPLY from={} commands={} ops=0",
						reply.fromNodeId(), reply.commands().size());
			}
			return;
		}
		ThreadService.getLogicExecutor().execute(() -> {
			for (ReplicationOp op : reply.ops()) {
				if (homologousRepair != null) {
					homologousRepair.observe(op);
				}
				final ReplicaApplier applier = applierByDomain.apply(op.domainType());
				if (applier != null) {
					applier.apply(op, false, true);
				}
			}
			log.debug("REPAIR_REPLY from={} commands={} ops={}",
					reply.fromNodeId(), reply.commands().size(), reply.ops().size());
		});
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		final String remoteId = ctx.channel().attr(REMOTE_NODE_ATTR).get();
		if (remoteId != null) {
			remoteChannels.remove(remoteId, ctx.channel());
			final OrchidNode node = orchidNode.get();
			if (node != null) {
				node.forgetPeer(remoteId);
			}
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		log.warn("Replication channel error: {}", cause.toString());
		ctx.close();
	}
}
