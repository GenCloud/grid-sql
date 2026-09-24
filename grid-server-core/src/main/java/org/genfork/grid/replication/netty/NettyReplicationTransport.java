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

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
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
import org.genfork.grid.replication.netty.ReplicationRpcCodec.SealedShardPackMsg;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.orchid.OrchidTransport;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.repair.VersionLocus;
import org.genfork.grid.replication.transport.DiscoveredPeer;
import org.genfork.grid.replication.transport.ReplicationMessageType;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.sql.tx.ForUpdatePeerHeldKeys;
import org.genfork.grid.sql.tx.LockWaitCancelledException;
import org.genfork.grid.sql.tx.LockWaitTimeoutException;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Netty-only replication transport (ORCHID + OpLog ship + repair). No in-process bus.
 * <p>
 * Phase digest/ACK burst: encode once, {@code write} then coalesce {@code flush} per peer;
 * multi-digest → {@link ReplicationMessageType#ORCHID_PHASE_BATCH} (count + repeated phase payloads).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class NettyReplicationTransport implements OrchidTransport, AutoCloseable {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NettyReplicationTransport.class);
	/**
	 * Initial delay before first missing-peer reconnect attempt (ms).
	 */
	public static final long RECONNECT_INITIAL_DELAY_MS = 50L;
	/**
	 * Period for missing-peer reconnect (ms); kept near region claim poll for fast revive fence.
	 */
	public static final long RECONNECT_PERIOD_MS = 100L;
	/**
	 * Extra budget beyond peer {@link SqlRecordLockManager#lockWaitTimeoutMs()} for RPC RTT
	 * when waiting for {@link ReplicationMessageType#FOR_UPDATE_LOCK_ACK} (logic VT only).
	 */
	private static final long FOR_UPDATE_LOCK_ACK_SLACK_MS = 1000L;
	/**
	 * Fallback ACK wait when no lock manager is bound.
	 */
	private static final long FOR_UPDATE_LOCK_ACK_TIMEOUT_MS = SqlRecordLockManager.DEFAULT_LOCK_WAIT_MS + FOR_UPDATE_LOCK_ACK_SLACK_MS;
	private static final String FOR_UPDATE_WAITER_SEP = "@";
	private final String localNodeId;
	private final String clusterId;
	private final String localDc;
	private final AtomicLong schemaEpoch;
	private final String bindHost;
	private final int bindPort;
	private final long connectTimeoutMs;
	private final int maxFrameBytes;
	private final List<ReplicationPeer> peers;
	private final Map<String, Channel> channelsByPeer = new ConcurrentHashMap<>();
	private final Map<String, Long> lastPushEpochMs = new ConcurrentHashMap<>();
	private final AtomicLong emaRttMs = new AtomicLong(0);
	private final AtomicBoolean started = new AtomicBoolean();
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private Channel serverChannel;
	private Supplier<OrchidNode> orchidNode = () -> null;
	private Function<String, ReplicaApplier> applierByDomain = _ -> null;
	private CrossDcPublisher crossDcPublisher;
	private ReplicationNodeState nodeState;
	private HomologousRepair homologousRepair;
	private OpLog opLog;
	private SparseCatchUp sparseCatchUp;
	private Consumer<DiscoveredPeer> onPeerHello = _ -> {
	};
	private Consumer<String> onApplyAckPeer = _ -> {
	};
	private Consumer<SealedShardPackMsg> sealedShardPackHandler = _ -> {
	};
	private Function<ReplicationRpcCodec.RegionClaimReq, byte[]> regionClaimReqHandler = _ -> null;
	private Consumer<ReplicationRpcCodec.RegionClaimAck> regionClaimAckHandler = _ -> {
	};
	private Supplier<Long> regionEpochSupplier = () -> 0L;
	private Supplier<Byte> regionRoleSupplier = () -> (byte) 0;
	/**
	 * Peer FOR UPDATE lock SoT on this node (logic VT handlers only).
	 */
	private final AtomicReference<SqlRecordLockManager> forUpdateLockManager = new AtomicReference<>();
	/**
	 * Peer-held FOR UPDATE keys by txId (prepare vote + abort release).
	 */
	private final ForUpdatePeerHeldKeys forUpdatePeerHeldKeys = new ForUpdatePeerHeldKeys();
	/**
	 * In-flight FOR_UPDATE_LOCK_REQ waiters keyed by {@code peerId@txId}.
	 * Completed on ACK; never parked on Netty EL (caller {@code get} on logic VT).
	 */
	private final ConcurrentHashMap<String, CompletableFuture<Boolean>> forUpdateLockWaiters = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, CompletableFuture<Boolean>> forUpdatePrepareWaiters = new ConcurrentHashMap<>();
	/**
	 * VT/logic-thread coalesce of APPLY_ACK while applying an OpLog segment (not Netty EL).
	 */
	private final ThreadLocal<List<ApplyAck>> coalesceApplyAcks = ThreadLocal.withInitial(ArrayList::new);
	private final ThreadLocal<Boolean> applyAckCoalesceActive = ThreadLocal.withInitial(() -> Boolean.FALSE);

	public NettyReplicationTransport(String localNodeId, String clusterId, String localDc, long schemaEpoch, String bindHost, int bindPort, long connectTimeoutMs, int maxFrameBytes, List<ReplicationPeer> peers) {
		this.localNodeId = localNodeId;
		this.clusterId = clusterId;
		this.localDc = localDc;
		this.schemaEpoch = new AtomicLong(schemaEpoch);
		this.bindHost = bindHost;
		this.bindPort = bindPort;
		this.connectTimeoutMs = connectTimeoutMs;
		this.maxFrameBytes = maxFrameBytes;
		this.peers = new CopyOnWriteArrayList<>(peers == null ? List.of() : peers);
	}

	/**
	 * Dynamic membership: add peer and connect if transport already started.
	 */
	public void setSchemaEpoch(long epoch) {
		schemaEpoch.set(epoch);
	}

	public long schemaEpoch() {
		return schemaEpoch.get();
	}

	public void addPeer(ReplicationPeer peer) {
		if (peer == null || peer.id() == null) {
			return;
		}
		peers.removeIf(p -> peer.id().equals(p.id()));
		peers.add(peer);
		if (started.get()) {
			connectPeer(peer);
		}
	}

	public void removePeer(String peerId) {
		if (peerId == null) {
			return;
		}
		peers.removeIf(p -> peerId.equals(p.id()));
		final Channel ch = channelsByPeer.remove(peerId);
		if (ch != null) {
			ch.close();
		}
	}

	public List<ReplicationPeer> peers() {
		return List.copyOf(peers);
	}

	/**
	 * True when at least one peer in a different DC has an active Netty channel.
	 * Used by Cross-DC learners to fail-closed reads during DC-link partition.
	 */
	public boolean hasActivePeerOutsideDc(String localDc) {
		if (localDc == null || localDc.isEmpty()) {
			return false;
		}
		for (ReplicationPeer peer : peers) {
			if (peer.dc() == null || localDc.equals(peer.dc())) {
				continue;
			}
			final Channel ch = channelsByPeer.get(peer.id());
			if (ch != null && ch.isActive()) {
				return true;
			}
		}
		return false;
	}

	public long emaRttMs() {
		return emaRttMs.get();
	}

	/**
	 * Record push timestamp; returns measured RTT ms when ACK arrives (0 if unknown).
	 */
	public long notePeerAck(String peerId) {
		final Long sent = lastPushEpochMs.remove(peerId);
		if (sent == null) {
			return 0L;
		}
		final long rtt = Math.max(0L, System.currentTimeMillis() - sent);
		emaRttMs.updateAndGet(prev -> prev == 0L ? rtt : (prev * 7L + rtt) / 8L);
		return rtt;
	}

	public void bindHandlers(Supplier<OrchidNode> orchidNode, Function<String, ReplicaApplier> applierByDomain, CrossDcPublisher crossDcPublisher, ReplicationNodeState nodeState, HomologousRepair homologousRepair, OpLog opLog, SparseCatchUp sparseCatchUp, Consumer<DiscoveredPeer> onPeerHello, Consumer<String> onApplyAckPeer, Supplier<Long> regionEpochSupplier, Supplier<Byte> regionRoleSupplier) {
		this.orchidNode = orchidNode;
		this.applierByDomain = applierByDomain;
		this.crossDcPublisher = crossDcPublisher;
		this.nodeState = nodeState;
		this.homologousRepair = homologousRepair;
		this.opLog = opLog;
		this.sparseCatchUp = sparseCatchUp;
		this.onPeerHello = onPeerHello == null ? _ -> {
		} : onPeerHello;
		this.onApplyAckPeer = onApplyAckPeer == null ? _ -> {
		} : onApplyAckPeer;
		this.regionEpochSupplier = regionEpochSupplier == null ? () -> 0L : regionEpochSupplier;
		this.regionRoleSupplier = regionRoleSupplier == null ? () -> (byte) 0 : regionRoleSupplier;
	}

	/**
	 * Receive handler for {@link ReplicationMessageType#SEALED_SHARD_PACK} (logic/VT thread).
	 */
	public void setSealedShardPackHandler(Consumer<SealedShardPackMsg> handler) {
		this.sealedShardPackHandler = handler == null ? _ -> {
		} : handler;
	}

	/**
	 * Current Multi-DC region epoch for HELLO (0 when fencing off).
	 */
	public long currentRegionEpoch() {
		return regionEpochSupplier.get();
	}

	/**
	 * Current Multi-DC region role wire code for HELLO (0 when fencing off).
	 */
	public byte currentRegionRoleWire() {
		return regionRoleSupplier.get();
	}

	/**
	 * Re-broadcast HELLO with current schema/region fence (claim notify; no new opcode).
	 */
	public void broadcastHello() {
		final Hello hello = new Hello(localNodeId, clusterId, localDc, schemaEpoch.get(), currentRegionEpoch(), currentRegionRoleWire());
		broadcastEncoded(new WireMessage(ReplicationMessageType.HELLO.opcode(), ReplicationRpcCodec.encodeHello(hello)));
	}

	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		bossGroup = new NioEventLoopGroup(1, Thread.ofPlatform().name("repl-boss-", 0).factory());
		workerGroup = new NioEventLoopGroup(4, Thread.ofPlatform().name("repl-io-", 0).factory());
		final ServerBootstrap server = new ServerBootstrap();
		server.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).childOption(ChannelOption.TCP_NODELAY, true).childOption(ChannelOption.SO_KEEPALIVE, true).childHandler(new ChannelInitializer<SocketChannel>() {
			@Override
			protected void initChannel(SocketChannel ch) {
				configurePipeline(ch.pipeline());
			}
		});
		try {
			serverChannel = server.bind(bindHost, bindPort).sync().channel();
			log.info("Replication Netty listening on {}:{}", bindHost, bindPort);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Failed to bind replication port " + bindPort, e);
		}
		for (ReplicationPeer peer : peers) {
			connectPeer(peer);
		}
		workerGroup.scheduleAtFixedRate(this::reconnectMissing, RECONNECT_INITIAL_DELAY_MS, RECONNECT_PERIOD_MS, TimeUnit.MILLISECONDS);
	}

	private void configurePipeline(ChannelPipeline pipeline) {
		pipeline.addLast(new ReplicationFrameCodec.Decoder(maxFrameBytes));
		pipeline.addLast(new ReplicationFrameCodec.Encoder());
		pipeline.addLast(new ReplicationInboundHandler(localNodeId, clusterId, localDc, schemaEpoch, orchidNode, applierByDomain, crossDcPublisher, nodeState, homologousRepair, channelsByPeer, this, opLog, sparseCatchUp, onPeerHello, onApplyAckPeer));
	}

	private void connectPeer(ReplicationPeer peer) {
		if (peer.id().equals(localNodeId)) {
			return;
		}
		final Channel existing = channelsByPeer.get(peer.id());
		if (existing != null && existing.isActive()) {
			return;
		}
		final Bootstrap bootstrap = new Bootstrap();
		bootstrap.group(workerGroup).channel(NioSocketChannel.class).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(connectTimeoutMs, Integer.MAX_VALUE)).option(ChannelOption.TCP_NODELAY, true).handler(new ChannelInitializer<SocketChannel>() {
			@Override
			protected void initChannel(SocketChannel ch) {
				configurePipeline(ch.pipeline());
			}
		});
		bootstrap.connect(peer.host(), peer.port()).addListener((ChannelFutureListener) future -> {
			if (future.isSuccess()) {
				channelsByPeer.put(peer.id(), future.channel());
				log.info("Connected replication peer {} at {}:{}", peer.id(), peer.host(), peer.port());
				final OrchidNode node = orchidNode.get();
				if (node != null) {
					node.onPeerAvailable(peer.id());
				}
			} else {
				ReplicationMetrics.recordConnectFailure();
				log.debug("Connect to peer {} failed: {}", peer.id(), future.cause() == null ? "unknown" : future.cause().toString());
			}
		});
	}

	private void reconnectMissing() {
		if (!started.get()) {
			return;
		}
		for (ReplicationPeer peer : peers) {
			final Channel ch = channelsByPeer.get(peer.id());
			if (ch == null || !ch.isActive()) {
				connectPeer(peer);
			}
		}
	}

	public void pushSegment(String peerId, OpLogSegment segment) {
		lastPushEpochMs.put(peerId, System.currentTimeMillis());
		write(peerId, new WireMessage(ReplicationMessageType.OPLOG_PUSH.opcode(), OpLogCodec.encodeSegment(segment)));
		ReplicationMetrics.recordOplogPushSent();
	}

	/**
	 * Ship sealed shard artifacts ({@code .gmap}/{@code .sbpt}/{@code .sbm}) to {@code peerId}.
	 * Used by {@code ShardMigrator} CATCH_UP — not a second protocol beyond {@link ReplicationMessageType#SEALED_SHARD_PACK}.
	 */
	public void pushSealedShardPack(String peerId, String domainType, int shard, byte[] packed) {
		write(peerId, new WireMessage(ReplicationMessageType.SEALED_SHARD_PACK.opcode(), ReplicationRpcCodec.encodeSealedShardPack(localNodeId, domainType, shard, packed)));
	}

	/**
	 * Decode + dispatch sealed pack off Netty EL (handler may unpack files / open readers).
	 */
	public void applySealedShardPack(byte[] body) {
		final SealedShardPackMsg msg = ReplicationRpcCodec.decodeSealedShardPack(body);
		sealedShardPackHandler.accept(msg);
	}

	public void sendApplyAck(String peerId, ReplicationOp op) {
		final ApplyAck ack = new ApplyAck(localNodeId, op.domainType(), op.shard(), op.opSeq());
		if (Boolean.TRUE.equals(applyAckCoalesceActive.get())) {
			coalesceApplyAcks.get().add(ack);
			return;
		}
		write(peerId, new WireMessage(ReplicationMessageType.APPLY_ACK.opcode(), ReplicationRpcCodec.encodeApplyAck(ack)));
		ReplicationMetrics.recordApplyAckSent();
	}

	public void sendApplyNack(String peerId, ReplicationOp op, String reason) {
		final ApplyNack nack = new ApplyNack(localNodeId, op.domainType(), op.shard(), op.opSeq(), reason);
		write(peerId, new WireMessage(ReplicationMessageType.APPLY_NACK.opcode(), ReplicationRpcCodec.encodeApplyNack(nack)));
	}

	public void broadcastApplyAck(ReplicationOp op) {
		final ApplyAck ack = new ApplyAck(localNodeId, op.domainType(), op.shard(), op.opSeq());
		if (Boolean.TRUE.equals(applyAckCoalesceActive.get())) {
			coalesceApplyAcks.get().add(ack);
			return;
		}
		final byte[] body = ReplicationRpcCodec.encodeApplyAck(ack);
		final WireMessage message = new WireMessage(ReplicationMessageType.APPLY_ACK.opcode(), body);
		broadcastEncoded(message);
		ReplicationMetrics.recordApplyAckSent();
	}

	/**
	 * Coalesce APPLY_ACK while {@code action} runs on the calling logic/VT thread, then flush one
	 * {@link ReplicationMessageType#APPLY_ACK_BATCH} (or single ACK) per peer. Not for Netty EL.
	 */
	public void runWithApplyAckCoalesce(Runnable action) {
		if (action == null) {
			return;
		}
		applyAckCoalesceActive.set(Boolean.TRUE);
		try {
			action.run();
			flushCoalescedApplyAcks();
		} finally {
			coalesceApplyAcks.get().clear();
			applyAckCoalesceActive.set(Boolean.FALSE);
		}
	}

	private void flushCoalescedApplyAcks() {
		final List<ApplyAck> acks = coalesceApplyAcks.get();
		if (acks.isEmpty()) {
			return;
		}
		final List<ApplyAck> snapshot = new ArrayList<>(acks);
		acks.clear();
		final WireMessage message;
		if (snapshot.size() == 1) {
			message = new WireMessage(ReplicationMessageType.APPLY_ACK.opcode(), ReplicationRpcCodec.encodeApplyAck(snapshot.getFirst()));
		} else {
			message = new WireMessage(ReplicationMessageType.APPLY_ACK_BATCH.opcode(), ReplicationRpcCodec.encodeApplyAckBatch(localNodeId, snapshot));
		}
		broadcastEncoded(message);
		for (int i = 0; i < snapshot.size(); i++) {
			ReplicationMetrics.recordApplyAckSent();
		}
	}

	public void pullOps(String peerId, String domainType, int shard, long fromSeqInclusive) {
		write(peerId, new WireMessage(ReplicationMessageType.OPLOG_PULL.opcode(), ReplicationRpcCodec.encodeOplogPull(localNodeId, domainType, shard, fromSeqInclusive)));
	}

	public void sendRepairRequest(String peerId, String domainType, int shard, Map<Long, VersionLocus> view) {
		write(peerId, new WireMessage(ReplicationMessageType.REPAIR_REQUEST.opcode(), ReplicationRpcCodec.encodeRepairRequest(localNodeId, domainType, shard, view)));
	}

	/**
	 * Broadcast Multi-DC region claim request to all active peers (Hold/Witness voters ACK async).
	 */
	public void broadcastRegionClaimReq(long claimId, long proposedEpoch, byte claimantRoleWire) {
		final byte[] body = ReplicationRpcCodec.encodeRegionClaimReq(claimId, proposedEpoch, localNodeId, localDc, claimantRoleWire);
		broadcastEncoded(new WireMessage(ReplicationMessageType.REGION_CLAIM_REQ.opcode(), body));
	}

	public void sendRegionClaimAck(String peerId, long claimId, byte voterRoleWire) {
		write(peerId, new WireMessage(ReplicationMessageType.REGION_CLAIM_ACK.opcode(), ReplicationRpcCodec.encodeRegionClaimAck(claimId, localNodeId, voterRoleWire)));
	}

	public void setRegionClaimHandlers(Function<ReplicationRpcCodec.RegionClaimReq, byte[]> onReq, Consumer<ReplicationRpcCodec.RegionClaimAck> onAck) {
		this.regionClaimReqHandler = onReq == null ? _ -> null : onReq;
		this.regionClaimAckHandler = onAck == null ? _ -> {
		} : onAck;
	}

	/**
	 * @return ACK body to reply, or {@code null} to ignore
	 */
	public byte[] handleRegionClaimReq(byte[] body) {
		final ReplicationRpcCodec.RegionClaimReq req = ReplicationRpcCodec.decodeRegionClaimReq(body);
		return regionClaimReqHandler.apply(req);
	}

	public void handleRegionClaimAck(byte[] body) {
		regionClaimAckHandler.accept(ReplicationRpcCodec.decodeRegionClaimAck(body));
	}

	/**
	 * Bind local {@link SqlRecordLockManager} for inbound {@code FOR_UPDATE_LOCK_*} (logic VT).
	 */
	public void setForUpdateLockManager(SqlRecordLockManager locks) {
		forUpdateLockManager.set(locks);
	}

	@VisibleForTesting
	public SqlRecordLockManager forUpdateLockManager() {
		return forUpdateLockManager.get();
	}

	/**
	 * Send {@link ReplicationMessageType#FOR_UPDATE_LOCK_REQ} and wait for ACK on the caller
	 * thread (must be logic VT — never Netty EL).
	 *
	 * @return {@code true} when peer granted; {@code false} for skip-locked miss / inactive peer
	 * @throws LockWaitTimeoutException when blocking acquire times out or peer NACK'd
	 * @throws LockWaitCancelledException when interrupted while waiting
	 */
	public boolean sendForUpdateLockReq(String peerId, long txId, boolean skipLocked, String table, byte[] key) {
		Objects.requireNonNull(peerId, "peerId");
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		if (!hasActivePeerChannel(peerId)) {
			if (skipLocked) {
				return false;
			}
			throw new LockWaitTimeoutException("FOR UPDATE peer channel inactive peerId=" + peerId + " table=" + table);
		}
		final String waiterKey = forUpdateWaiterKey(peerId, txId);
		final CompletableFuture<Boolean> waiter = new CompletableFuture<>();
		final CompletableFuture<Boolean> prev = forUpdateLockWaiters.putIfAbsent(waiterKey, waiter);
		if (prev != null) {
			throw new IllegalStateException("overlapping FOR_UPDATE_LOCK_REQ peerId=" + peerId + " txId=" + txId);
		}
		final byte[] body = ReplicationRpcCodec.encodeForUpdateLockReq(txId, skipLocked, table, key);
		write(peerId, new WireMessage(ReplicationMessageType.FOR_UPDATE_LOCK_REQ.opcode(), body));
		try {
			final Boolean granted = waiter.get(forUpdateLockAckTimeoutMs(), TimeUnit.MILLISECONDS);
			if (Boolean.TRUE.equals(granted)) {
				return true;
			}
			if (skipLocked) {
				return false;
			}
			throw new LockWaitTimeoutException("FOR UPDATE peer lock denied/timeout peerId=" + peerId + " table=" + table);
		} catch (TimeoutException ex) {
			forUpdateLockWaiters.remove(waiterKey, waiter);
			if (skipLocked) {
				return false;
			}
			throw new LockWaitTimeoutException("FOR UPDATE peer lock ACK timeout peerId=" + peerId + " table=" + table);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			forUpdateLockWaiters.remove(waiterKey, waiter);
			throw new LockWaitCancelledException();
		} catch (ExecutionException ex) {
			forUpdateLockWaiters.remove(waiterKey, waiter);
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("FOR UPDATE peer lock ACK failed peerId=" + peerId, cause);
		} finally {
			forUpdateLockWaiters.remove(waiterKey, waiter);
		}
	}

	/**
	 * Fire-and-forget {@link ReplicationMessageType#FOR_UPDATE_LOCK_RELEASE}.
	 */
	public void sendForUpdateLockRelease(String peerId, long txId, String table, byte[] key) {
		Objects.requireNonNull(peerId, "peerId");
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		final byte[] body = ReplicationRpcCodec.encodeForUpdateLockRelease(txId, table, key);
		write(peerId, new WireMessage(ReplicationMessageType.FOR_UPDATE_LOCK_RELEASE.opcode(), body));
	}

	/**
	 * Inbound {@link ReplicationMessageType#FOR_UPDATE_LOCK_REQ} on logic VT.
	 *
	 * @return ACK body (always non-null)
	 */
	public byte[] handleForUpdateLockReq(byte[] body) {
		final ReplicationRpcCodec.ForUpdateLockReq req = ReplicationRpcCodec.decodeForUpdateLockReq(body);
		boolean granted = false;
		final SqlRecordLockManager locks = forUpdateLockManager.get();
		if (locks != null) {
			try {
				if (req.skipLocked()) {
					granted = locks.tryLock(req.table(), req.key());
				} else {
					locks.lock(req.table(), req.key());
					granted = true;
				}
			} catch (LockWaitTimeoutException | LockWaitCancelledException ex) {
				granted = false;
			}
		}
		if (granted) {
			forUpdatePeerHeldKeys.remember(req.txId(), req.table(), req.key());
		}
		return ReplicationRpcCodec.encodeForUpdateLockAck(req.txId(), granted, localNodeId);
	}

	/**
	 * Complete pending waiter for {@link ReplicationMessageType#FOR_UPDATE_LOCK_ACK}.
	 */
	public void handleForUpdateLockAck(byte[] body) {
		final ReplicationRpcCodec.ForUpdateLockAck ack = ReplicationRpcCodec.decodeForUpdateLockAck(body);
		final String waiterKey = forUpdateWaiterKey(ack.fromNodeId(), ack.txId());
		final CompletableFuture<Boolean> waiter = forUpdateLockWaiters.remove(waiterKey);
		if (waiter != null) {
			waiter.complete(ack.granted());
		}
	}

	/**
	 * Inbound {@link ReplicationMessageType#FOR_UPDATE_LOCK_RELEASE} on logic VT.
	 */
	public void handleForUpdateLockRelease(byte[] body) {
		final ReplicationRpcCodec.ForUpdateLockRelease rel = ReplicationRpcCodec.decodeForUpdateLockRelease(body);
		forUpdatePeerHeldKeys.forget(rel.txId(), rel.table(), rel.key());
		final SqlRecordLockManager locks = forUpdateLockManager.get();
		if (locks == null) {
			return;
		}
		try {
			locks.unlock(rel.table(), rel.key());
		} catch (RuntimeException ignored) {
		}
		// idempotent release
	}

	/**
	 * Send {@link ReplicationMessageType#FOR_UPDATE_PREPARE_REQ} and wait for ACK (logic VT only).
	 *
	 * @return {@code true} when peer prepared
	 */
	public boolean sendForUpdatePrepareReq(String peerId, long txId) {
		Objects.requireNonNull(peerId, "peerId");
		if (!hasActivePeerChannel(peerId)) {
			return false;
		}
		final String waiterKey = forUpdateWaiterKey(peerId, txId);
		final CompletableFuture<Boolean> waiter = new CompletableFuture<>();
		final CompletableFuture<Boolean> prev = forUpdatePrepareWaiters.putIfAbsent(waiterKey, waiter);
		if (prev != null) {
			throw new IllegalStateException("overlapping FOR_UPDATE_PREPARE_REQ peerId=" + peerId + " txId=" + txId);
		}
		final byte[] body = ReplicationRpcCodec.encodeForUpdatePrepareReq(txId, localNodeId);
		write(peerId, new WireMessage(ReplicationMessageType.FOR_UPDATE_PREPARE_REQ.opcode(), body));
		try {
			final Boolean prepared = waiter.get(forUpdateLockAckTimeoutMs(), TimeUnit.MILLISECONDS);
			return prepared != null && prepared;
		} catch (TimeoutException ex) {
			waiter.complete(false);
			return false;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			waiter.complete(false);
			return false;
		} catch (ExecutionException ex) {
			return false;
		} finally {
			forUpdatePrepareWaiters.remove(waiterKey, waiter);
		}
	}

	/**
	 * Fan-out {@link ReplicationMessageType#FOR_UPDATE_COMMIT_DEC} to all active peers.
	 */
	public void broadcastForUpdateCommitDec(long txId, boolean commit) {
		final byte[] body = ReplicationRpcCodec.encodeForUpdateCommitDec(txId, commit, localNodeId);
		broadcastEncoded(new WireMessage(ReplicationMessageType.FOR_UPDATE_COMMIT_DEC.opcode(), body));
	}

	/**
	 * Inbound prepare: vote yes when this peer still holds ≥1 FOR UPDATE key for {@code txId}.
	 */
	public byte[] handleForUpdatePrepareReq(byte[] body) {
		final ReplicationRpcCodec.ForUpdatePrepareReq req = ReplicationRpcCodec.decodeForUpdatePrepareReq(body);
		final boolean prepared = forUpdateLockManager.get() != null && forUpdatePeerHeldKeys.hasAny(req.txId());
		return ReplicationRpcCodec.encodeForUpdatePrepareAck(req.txId(), prepared, localNodeId);
	}

	public void handleForUpdatePrepareAck(byte[] body) {
		final ReplicationRpcCodec.ForUpdatePrepareAck ack = ReplicationRpcCodec.decodeForUpdatePrepareAck(body);
		final String waiterKey = forUpdateWaiterKey(ack.fromNodeId(), ack.txId());
		final CompletableFuture<Boolean> waiter = forUpdatePrepareWaiters.remove(waiterKey);
		if (waiter != null) {
			waiter.complete(ack.prepared());
		}
	}

	/**
	 * Inbound commit decision: on abort, release peer-held keys for {@code txId}; commit is informational
	 * (locks released via {@code LOCK_RELEASE} / TX end).
	 */
	public void handleForUpdateCommitDec(byte[] body) {
		final ReplicationRpcCodec.ForUpdateCommitDec dec = ReplicationRpcCodec.decodeForUpdateCommitDec(body);
		if (!dec.commit()) {
			forUpdatePeerHeldKeys.releaseAll(dec.txId(), forUpdateLockManager.get());
		}
	}

	@VisibleForTesting
	CompletableFuture<Boolean> offerForUpdateLockWaiter(String peerId, long txId) {
		final CompletableFuture<Boolean> waiter = new CompletableFuture<>();
		final CompletableFuture<Boolean> prev = forUpdateLockWaiters.putIfAbsent(forUpdateWaiterKey(peerId, txId), waiter);
		return prev != null ? prev : waiter;
	}

	private boolean hasActivePeerChannel(String peerId) {
		final Channel ch = channelsByPeer.get(peerId);
		return ch != null && ch.isActive();
	}

	private long forUpdateLockAckTimeoutMs() {
		final SqlRecordLockManager locks = forUpdateLockManager.get();
		if (locks != null) {
			return Math.max(1L, locks.lockWaitTimeoutMs() + FOR_UPDATE_LOCK_ACK_SLACK_MS);
		}
		return FOR_UPDATE_LOCK_ACK_TIMEOUT_MS;
	}

	private static String forUpdateWaiterKey(String peerId, long txId) {
		return peerId + FOR_UPDATE_WAITER_SEP + txId;
	}

	private void write(String peerId, WireMessage message) {
		final Channel ch = channelsByPeer.get(peerId);
		if (ch == null || !ch.isActive()) {
			log.debug("No active channel for peer {} (opcode={})", peerId, message.opcode());
			return;
		}
		ch.writeAndFlush(message);
	}

	private void writeNoFlush(String peerId, WireMessage message) {
		final Channel ch = channelsByPeer.get(peerId);
		if (ch == null || !ch.isActive()) {
			log.debug("No active channel for peer {} (opcode={})", peerId, message.opcode());
			return;
		}
		ch.write(message);
	}

	private void flushPeer(String peerId) {
		final Channel ch = channelsByPeer.get(peerId);
		if (ch != null && ch.isActive()) {
			ch.flush();
		}
	}

	private void broadcast(WireMessage message) {
		broadcastEncoded(message);
	}

	/**
	 * Encode-once fan-out: write then flush (coalesce ACK / phase burst).
	 */
	private void broadcastEncoded(WireMessage message) {
		final List<Channel> active = new ArrayList<>(channelsByPeer.size());
		for (Map.Entry<String, Channel> e : channelsByPeer.entrySet()) {
			final Channel ch = e.getValue();
			if (ch != null && ch.isActive()) {
				ch.write(message);
				active.add(ch);
			}
		}
		for (Channel ch : active) {
			ch.flush();
		}
	}

	private void writeEncodedToMany(Collection<String> toNodeIds, WireMessage message) {
		if (toNodeIds == null || toNodeIds.isEmpty()) {
			return;
		}
		final List<String> flushed = new ArrayList<>(toNodeIds.size());
		for (String peerId : toNodeIds) {
			if (peerId == null || peerId.equals(localNodeId)) {
				continue;
			}
			writeNoFlush(peerId, message);
			flushed.add(peerId);
		}
		for (String peerId : flushed) {
			flushPeer(peerId);
		}
	}

	@Override
	public void sendPhase(String toNodeId, OrchidPhaseMessage message) {
		write(toNodeId, new WireMessage(ReplicationMessageType.ORCHID_PHASE.opcode(), ReplicationRpcCodec.encodePhase(message)));
		ReplicationMetrics.recordOrchidRpcSent();
	}

	@Override
	public void broadcastPhase(OrchidPhaseMessage message) {
		broadcastEncoded(new WireMessage(ReplicationMessageType.ORCHID_PHASE.opcode(), ReplicationRpcCodec.encodePhase(message)));
		ReplicationMetrics.recordOrchidRpcSent();
	}

	@Override
	public void sendPhaseToMany(Collection<String> toNodeIds, OrchidPhaseMessage message) {
		if (toNodeIds == null || toNodeIds.isEmpty() || message == null) {
			return;
		}
		final WireMessage wire = new WireMessage(ReplicationMessageType.ORCHID_PHASE.opcode(), ReplicationRpcCodec.encodePhase(message));
		writeEncodedToMany(toNodeIds, wire);
		ReplicationMetrics.recordOrchidRpcSent();
	}

	@Override
	public void sendPhaseDigestsToMany(Collection<String> toNodeIds, OrchidPhaseBatchMessage message) {
		if (toNodeIds == null || toNodeIds.isEmpty() || message == null) {
			return;
		}
		sendPhaseMessagesToMany(toNodeIds, ReplicationRpcCodec.expandPhaseBatch(message));
	}

	@Override
	public void sendPhaseMessagesToMany(Collection<String> toNodeIds, List<OrchidPhaseMessage> messages) {
		if (toNodeIds == null || toNodeIds.isEmpty() || messages == null || messages.isEmpty()) {
			return;
		}
		final WireMessage wire;
		if (messages.size() == 1) {
			wire = new WireMessage(ReplicationMessageType.ORCHID_PHASE.opcode(), ReplicationRpcCodec.encodePhase(messages.getFirst()));
		} else {
			wire = new WireMessage(ReplicationMessageType.ORCHID_PHASE_BATCH.opcode(), ReplicationRpcCodec.encodePhaseMessageBatch(messages));
		}
		writeEncodedToMany(toNodeIds, wire);
		ReplicationMetrics.recordOrchidRpcSent();
	}

	@Override
	public void sendPropose(String toNodeId, OrchidProposeMessage message) {
		write(toNodeId, new WireMessage(ReplicationMessageType.ORCHID_PROPOSE.opcode(), ReplicationRpcCodec.encodePropose(message)));
	}

	@Override
	public void broadcastPropose(OrchidProposeMessage message) {
		broadcastEncoded(new WireMessage(ReplicationMessageType.ORCHID_PROPOSE.opcode(), ReplicationRpcCodec.encodePropose(message)));
	}

	@Override
	public void sendCommit(String toNodeId, OrchidCommitMessage message) {
		write(toNodeId, new WireMessage(ReplicationMessageType.ORCHID_COMMIT.opcode(), ReplicationRpcCodec.encodeCommit(message)));
	}

	@Override
	public void broadcastCommit(OrchidCommitMessage message) {
		broadcastEncoded(new WireMessage(ReplicationMessageType.ORCHID_COMMIT.opcode(), ReplicationRpcCodec.encodeCommit(message)));
	}

	@Override
	public void sendNack(String toNodeId, OrchidNackMessage message) {
		write(toNodeId, new WireMessage(ReplicationMessageType.ORCHID_NACK.opcode(), ReplicationRpcCodec.encodeOrchidNack(message)));
	}

	@Override
	public void close() {
		started.set(false);
		for (CompletableFuture<Boolean> waiter : forUpdateLockWaiters.values()) {
			waiter.complete(false);
		}
		forUpdateLockWaiters.clear();
		for (CompletableFuture<Boolean> waiter : forUpdatePrepareWaiters.values()) {
			waiter.complete(false);
		}
		forUpdatePrepareWaiters.clear();
		channelsByPeer.values().forEach(Channel::close);
		channelsByPeer.clear();
		if (serverChannel != null) {
			serverChannel.close();
		}
		if (bossGroup != null) {
			bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
		}
		if (workerGroup != null) {
			workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
		}
	}

	public String getLocalNodeId() {
		return this.localNodeId;
	}
}
