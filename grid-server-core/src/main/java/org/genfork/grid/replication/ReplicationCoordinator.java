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
package org.genfork.grid.replication;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationProps;
import org.genfork.grid.mem.adaptive.AdaptiveDiskFirstController;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.mem.stage.WorkingSetBudget;
import org.genfork.grid.overlay.OverlayStore;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.flow.ReplicationFlowControl;
import org.genfork.grid.replication.join.SparseCatchUp;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.log.StreamOpLogAppender;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.orchid.DigestQuorum;
import org.genfork.grid.replication.orchid.OrchidMultiDcConfig;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.region.RegionRoleCoordinator;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.schema.SchemaEpochSupport;
import org.genfork.grid.replication.snapshot.IndexCheckpointService;
import org.genfork.grid.replication.snapshot.SnapshotService;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.swarm.AdaptiveReplicaSwarm;
import org.genfork.grid.replication.swarm.HierarchicalPlacementOptimizer;
import org.genfork.grid.replication.swarm.PeerRole;
import org.genfork.grid.replication.swarm.PlacementPlan;
import org.genfork.grid.replication.swarm.PlacementTopology;
import org.genfork.grid.replication.swarm.ShardMigrateAction;
import org.genfork.grid.replication.swarm.ShardMigrator;
import org.genfork.grid.replication.swarm.SwarmMigrateBatchSizes;
import org.genfork.grid.replication.swarm.SwarmMigrateHysteresis;
import org.genfork.grid.replication.transport.ApplyAckSender;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.replication.transport.ReplicationPublisher;
import org.genfork.grid.replication.tx.StreamCommitSerializer;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.replication.util.CrossDcVoterUtil;
import org.genfork.grid.replication.pitr.OpLogArchiveStreamer;
import org.genfork.grid.replication.util.OpLogArchiveUtil;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.genfork.grid.sql.tx.DistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.NettyDistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import com.google.common.annotations.VisibleForTesting;

/**
 * Facade: ORCHID consensus + Netty-only transport + always-on-disk OpLog + swarm + homologous repair.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicationCoordinator {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReplicationCoordinator.class);
	/**
	 * OpLog / ORCHID domain for catalog DDL ({@link ReplicationOpType#DDL}).
	 */
	public static final String CATALOG_DOMAIN = "_catalog";
	public static final int CATALOG_SHARD = 0;
	private static final int FALLBACK_DEFAULT_SHARDS = 4;
	private static final long PROMOTION_STATE_POLL_INTERVAL_MS = 50L;
	private static final String OPLOG_SHUTDOWN_HOOK_NAME = "repl-oplog-shutdown";
	/**
	 * Default archive root when enabled but {@code dir} blank.
	 */
	private static final String DEFAULT_OPLOG_ARCHIVE_DIR = "./data/oplog-archive";
	private final ReplicationNodeState nodeState;
	private final OpLog opLog;
	/**
	 * Shared OpLog holdback for TX markers (multi-stream begin/commit) — same ordering as
	 * {@link MutationRecorder} data path. Direct {@link OpLog#appendDeferred} races concurrent data ops.
	 */
	private final StreamOpLogAppender streamOpLogAppender;
	private final ReplicationPublisher publisher;
	private final CrossDcPublisher crossDcPublisher;
	private final TxEnvelopeCoordinator txEnvelopeCoordinator;
	private final SnapshotService snapshotService;
	private final IndexCheckpointService indexCheckpointService;
	private final SealedGridMapService sealedGridMapService;
	/**
	 * PITR archive root when {@code grid.durability.oplog-archive.enabled}; else {@code null}.
	 * Archive I/O is sync on seal/truncateSafe callers (already off Netty EL).
	 */
	private final Path opLogArchiveRoot;
	/**
	 * Append-only off-node stream root when {@code stream-enabled}; else {@code null}.
	 */
	private final Path opLogArchiveStreamRoot;
	private final OrchidNode orchidNode;
	private final SparseCatchUp sparseCatchUp;
	private final HomologousRepair homologousRepair;
	private final AdaptiveReplicaSwarm swarm;
	private final ShardMigrator shardMigrator;
	private final boolean enabled;
	/**
	 * Peer Netty ship / swarm / repair (independent of local durability).
	 */
	private final boolean peerTransportEnabled;
	private final NettyReplicationTransport nettyTransport;
	private final AdaptiveDiskFirstController adaptiveDiskFirstController;
	private final Map<String, ReplicaApplier> appliers = new ConcurrentHashMap<>();
	private final Map<String, MutationRecorder> recorders = new ConcurrentHashMap<>();
	private final Map<String, Function<Integer, GridEntriesProcessor>> domainProcessors = new ConcurrentHashMap<>();
	private final Set<String> pendingSchemaBarriers = ConcurrentHashMap.newKeySet();
	private final ReplicationFlowControl flowControl;
	/**
	 * Serializes OpLog TX units per stream (prevents interleaved BEGIN under WRITE_BATCH).
	 */
	private final StreamCommitSerializer streamCommitSerializer;
	private final List<ReplicationPeer> peers;
	private final boolean swarmEnabled;
	private final boolean applyVoterSetHints;
	private final boolean applyAutoCutover;
	private final boolean crossDcEnabled;
	private final RegionRoleCoordinator regionCoordinator;
	private final boolean repairEnabled;
	private final long repairIntervalMs;
	/**
	 * Full locus reconcile cadence: every Nth {@link #repairTick} ships O(keys) views for checksum heal.
	 * Other ticks skip streams where the peer ACK is caught up or only soft-lagged (OpLog push
	 * already streams catch-up — avoid WRITE_ONLY / Capacity QG encode/disk storm).
	 */
	private static final long REPAIR_FULL_RECONCILE_EVERY = 60L;
	/**
	 * Soft lag: peer behind by fewer than this many ops is treated as in-flight push, not
	 * divergence. Locus reconcile still runs on {@link #REPAIR_FULL_RECONCILE_EVERY}.
	 */
	private static final long REPAIR_SOFT_LAG_OPS = 10000L;
	private final AtomicLong repairTickCount = new AtomicLong();
	/**
	 * Fallback shard count for PIN migrate gate when OpLog has no {@code domain#shard} streams yet.
	 */
	private final int defaultShardCount;
	private ScheduledExecutorService backgroundScheduler;
	private volatile OverlayStore overlayStore;
	private volatile ReplicaApplier.CatalogDdlHandler catalogDdlHandler;
	/**
	 * Idempotent stop for Spring destroy + JVM SIGTERM shutdown hook.
	 */
	private final AtomicBoolean stopped = new AtomicBoolean(false);
	private volatile Thread oplogShutdownHook;
	private final TxMarkerService txMarkerService;
	private final RegionClaimService regionClaimService;
	private final SealedHydrateService sealedHydrateService;
	private final PeerMembershipService peerMembershipService;

	public void setOverlayStore(OverlayStore overlayStore) {
		this.overlayStore = overlayStore;
	}

	public OverlayStore overlayStore() {
		return overlayStore;
	}

	/**
	 * Bind SQL record locks for inbound Netty {@code FOR_UPDATE_LOCK_*} (logic VT handlers).
	 * Also used by {@link #createNettyDistForUpdatePeerLockAgents()} factory for outbound agents.
	 */
	public void setForUpdateLockManager(SqlRecordLockManager locks) {
		if (nettyTransport != null) {
			nettyTransport.setForUpdateLockManager(locks);
		}
	}

	/**
	 * One {@link org.genfork.grid.sql.tx.NettyDistForUpdatePeerLockAgent} per configured peer.
	 * Empty when peer transport is off or peers list is empty.
	 */
	public List<DistForUpdatePeerLockAgent> createNettyDistForUpdatePeerLockAgents() {
		if (nettyTransport == null || !peerTransportEnabled) {
			return List.of();
		}
		final List<ReplicationPeer> peerList = nettyTransport.peers();
		if (peerList.isEmpty()) {
			return List.of();
		}
		final List<DistForUpdatePeerLockAgent> agents = new ArrayList<>(peerList.size());
		for (ReplicationPeer peer : peerList) {
			if (peer == null || peer.id() == null || peer.id().isBlank()) {
				continue;
			}
			if (peer.id().equals(nettyTransport.getLocalNodeId())) {
				continue;
			}
			agents.add(new NettyDistForUpdatePeerLockAgent(nettyTransport, peer.id()));
		}
		return List.copyOf(agents);
	}

	/**
	 * Single-peer factory for tests / explicit wiring.
	 */
	@VisibleForTesting
	public DistForUpdatePeerLockAgent createNettyDistForUpdatePeerLockAgent(String peerId) {
		Objects.requireNonNull(peerId, "peerId");
		if (nettyTransport == null) {
			throw new IllegalStateException("Netty transport not available");
		}
		return new NettyDistForUpdatePeerLockAgent(nettyTransport, peerId);
	}

	/**
	 * True when soft overlay has any live pin (metric / ops). Migrate filtering is per-shard —
	 * see {@link #isOverlayBlockingShardMigrate(String, int)}.
	 */
	public boolean isOverlayBlockingSwarmMigrate() {
		return overlayStore != null && overlayStore.hasAnyPinned();
	}

	/**
	 * True when a live pin hashes onto {@code domainType}/{@code shard} (TableStore shard rule).
	 */
	public boolean isOverlayBlockingShardMigrate(String domainType, int shard) {
		if (overlayStore == null || !overlayStore.isEnabled()) {
			return false;
		}
		return overlayStore.pinsDomainShard(domainType, shard, OpLogStreamKeyUtil.shardCountOf(opLog, domainType, defaultShardCount));
	}

	/**
	 * Bind SQL catalog DDL apply callback and ensure {@link #CATALOG_DOMAIN} applier/recorder exist.
	 * Idempotent; call from {@link org.genfork.grid.sql.SqlEngine} construction.
	 */
	public void bindSqlCatalog(ReplicaApplier.CatalogDdlHandler handler) {
		if (!enabled || handler == null) {
			return;
		}
		this.catalogDdlHandler = handler;
		if (!appliers.containsKey(CATALOG_DOMAIN)) {
			registerDomain(CATALOG_DOMAIN, shard -> null, false);
		}
		final ReplicaApplier catalogApplier = appliers.get(CATALOG_DOMAIN);
		if (catalogApplier != null) {
			catalogApplier.setCatalogDdlHandler(handler);
		}
	}

	public ReplicationCoordinator(GridConfigurationProperties properties) {
		final ReplicationProps cfg = properties.getReplication();
		final GridConfigurationProperties.DurabilityProps durability = properties.getDurability() == null ? new GridConfigurationProperties.DurabilityProps() : properties.getDurability();
		final boolean peerTransport = cfg != null && cfg.isEnabled();
		final boolean durable = peerTransport || durability.isEnabled();
		final GridConfigurationProperties.SqlProps sqlProps = properties.getSql();
		this.defaultShardCount = sqlProps == null ? FALLBACK_DEFAULT_SHARDS : Math.max(1, sqlProps.getDefaultShards());
		this.enabled = durable;
		this.peerTransportEnabled = peerTransport;
		final String hydrateMode = SealedHydrateService.normalizeHydrateMode(durability.getHydrateMode());
		final int workingSetMaxEntries = Math.max(0, durability.getWorkingSetMaxEntries());
		final boolean adaptiveDiskFirst = durability.isAdaptiveDiskFirst();
		if (!enabled) {
			this.adaptiveDiskFirstController = null;
			this.nodeState = null;
			this.opLog = null;
			this.streamOpLogAppender = null;
			this.streamCommitSerializer = null;
			this.publisher = null;
			this.crossDcPublisher = null;
			this.txEnvelopeCoordinator = null;
			this.snapshotService = null;
			this.indexCheckpointService = null;
			this.sealedGridMapService = null;
			this.opLogArchiveRoot = null;
			this.opLogArchiveStreamRoot = null;
			this.orchidNode = null;
			this.nettyTransport = null;
			this.flowControl = null;
			this.sparseCatchUp = null;
			this.homologousRepair = null;
			this.swarm = null;
			this.shardMigrator = null;
			this.peers = List.of();
			this.swarmEnabled = false;
			this.applyVoterSetHints = false;
			this.applyAutoCutover = false;
			this.crossDcEnabled = false;
			this.regionCoordinator = null;
			this.repairEnabled = false;
			this.repairIntervalMs = 0;
			this.txMarkerService = null;
			this.regionClaimService = null;
			this.sealedHydrateService = null;
			this.peerMembershipService = null;
			return;
		}
		this.adaptiveDiskFirstController = adaptiveDiskFirst ? new AdaptiveDiskFirstController(workingSetMaxEntries, "LAZY".equals(hydrateMode)) : null;
		this.nodeState = new ReplicationNodeState(cfg.getNodeId(), cfg.getClusterId(), cfg.getCrossDc().getLocalDc(), 1L);
		this.flowControl = new ReplicationFlowControl(cfg.getFlow().getMaxInflightOps(), cfg.getFlow().getDomains());
		final boolean fsync = cfg.getOpLog().isFsync();
		final Path dataDir = Path.of(cfg.getOpLog().getDataDir(), cfg.getClusterId(), cfg.getNodeId());
		this.opLog = new OpLog(dataDir, fsync);
		this.streamOpLogAppender = new StreamOpLogAppender(opLog);
		this.streamCommitSerializer = new StreamCommitSerializer();
		nodeState.seedAllFromOpLog(opLog);
		this.swarmEnabled = cfg.getSwarm().isEnabled();
		final GridConfigurationProperties.PlacementOptimizerProps optimizerCfgEarly = resolvePlacementOptimizer(cfg);
		this.applyVoterSetHints = optimizerCfgEarly.isApplyVoterSetHints();
		this.applyAutoCutover = cfg.getSwarm().isApplyAutoCutover();
		this.crossDcEnabled = cfg.getCrossDc().isEnabled();
		final boolean writeAdmission = cfg.getCrossDc().isWriteAdmission();
		this.regionCoordinator = RegionClaimService.createRegionCoordinator(cfg, dataDir, fsync);
		this.repairEnabled = cfg.getRepair().isHomologousEnabled();
		this.repairIntervalMs = Math.max(500L, cfg.getRepair().getReconcileIntervalMs());
		this.publisher = new ReplicationPublisher(nodeState, opLog, flowControl, cfg.getOpLog().getSegmentSize());
		this.txEnvelopeCoordinator = new TxEnvelopeCoordinator();
		final CrossDcMode crossDcMode = CrossDcMode.valueOf(cfg.getCrossDc().getMode());
		this.crossDcPublisher = new CrossDcPublisher(nodeState, crossDcMode, cfg.getCrossDc().getBatchMaxOps(), cfg.getCrossDc().getBatchMaxWaitMs(), cfg.getCrossDc().isRequireRemoteAck(), cfg.getCrossDc().getRemoteAckTimeoutMs(), cfg.getCrossDc().getLearners(), cfg.getCrossDc().getVoters());
		this.crossDcPublisher.setEnvelopeCoordinator(txEnvelopeCoordinator);
		this.snapshotService = new SnapshotService(opLog, nodeState);
		this.indexCheckpointService = new IndexCheckpointService(dataDir.resolve("index-ckpt"));
		this.opLogArchiveRoot = resolveOpLogArchiveRoot(durability);
		this.opLogArchiveStreamRoot = resolveOpLogArchiveStreamRoot(durability, opLogArchiveRoot);
		this.sealedGridMapService = new SealedGridMapService(dataDir.resolve("sealed"), opLogArchiveRoot);
		this.homologousRepair = repairEnabled ? new HomologousRepair(dataDir.resolve("locus")) : null;
		final long maxStaleLag = cfg.getHa() == null ? 10000L : Math.max(0L, cfg.getHa().getMaxStaleLag());
		final boolean replicaReadsEnabled = cfg.getHa() != null && cfg.getHa().isReplicaReadsEnabled();
		this.peers = new CopyOnWriteArrayList<>(PeerMembershipService.toPeers(peerTransportEnabled ? cfg.getTransport().getPeers() : List.of()));
		final String localDc = cfg.getCrossDc().getLocalDc();
		final List<String> localPeerIds = peers.stream().filter(p -> p.dc() == null || localDc.equals(p.dc())).map(ReplicationPeer::id).collect(Collectors.toList());
		final Set<String> remoteVoterIds = CrossDcVoterUtil.resolveRemoteVoters(crossDcMode, cfg.getCrossDc(), peers, localDc);
		this.nettyTransport = new NettyReplicationTransport(cfg.getNodeId(), cfg.getClusterId(), cfg.getCrossDc().getLocalDc(), nodeState.getSchemaEpoch(), cfg.getTransport().getBindHost(), cfg.getTransport().getBindPort(), cfg.getTransport().getConnectTimeoutMs(), cfg.getTransport().getMaxFrameBytes(), peers);
		final GridConfigurationProperties.OrchidProps orchidCfg = cfg.getOrchid();
		final DigestQuorum quorum = DigestQuorum.valueOf(orchidCfg.getDigestQuorum() == null ? "MAJORITY" : orchidCfg.getDigestQuorum());
		final Path orchidDir = dataDir.resolve("orchid");
		final OrchidMultiDcConfig multiDc = OrchidMultiDcConfig.of(remoteVoterIds, cfg.getCrossDc().isPhaseCoupling(), cfg.getCrossDc().getRemoteAckTimeoutMs());
		this.orchidNode = new OrchidNode(cfg.getNodeId(), orchidCfg.getCoupling(), orchidCfg.getNaturalFreqHz(), orchidCfg.getOrderThreshold(), orchidCfg.getTickMs(), quorum, nettyTransport, localPeerIds, orchidDir, fsync, multiDc, orchidCfg.getMaxProposeInFlight());
		nodeState.bindOrchid(orchidNode);
		final GridConfigurationProperties.SwarmProps swarmCfg = cfg.getSwarm();
		final GridConfigurationProperties.PlacementOptimizerProps optimizerCfg = resolvePlacementOptimizer(cfg);
		final SwarmMigrateHysteresis hysteresis = new SwarmMigrateHysteresis(swarmCfg.getMigrateThreshold(), swarmCfg.getMigrateEnterDelta(), swarmCfg.getMigrateExitDelta());
		final HierarchicalPlacementOptimizer optimizer = new HierarchicalPlacementOptimizer(optimizerCfg.isEnabled(), optimizerCfg.getDcPopulationSize(), optimizerCfg.getNodePopulationSize(), optimizerCfg.getGenerationsPerTick(), optimizerCfg.getMutationRate(), optimizerCfg.getDiversityWeight(), optimizerCfg.getMaxMigratesPerTick(), optimizerCfg.getSeed(), swarmCfg.getMigrateThreshold(), hysteresis.searchFloor(), optimizerCfg.isVoterSetHintsEnabled(), optimizerCfg.getTargetRemoteVoters());
		this.swarm = new AdaptiveReplicaSwarm(swarmCfg.getScoreWindowMs(), swarmCfg.getMigrateThreshold(), optimizer, hysteresis);
		this.shardMigrator = new ShardMigrator(opLog, nettyTransport, swarmCfg.getCutoverCooldownTicks());
		this.shardMigrator.setOpenTxGate(this::hasOpenTxOnStream);
		this.shardMigrator.setSealedGridMapService(sealedGridMapService);
		this.shardMigrator.setWriterEligibleGate(this::isWriterEligible);
		if (swarmEnabled) {
			crossDcPublisher.setSwarm(swarm);
			swarm.registerSensor("queueDepth", () -> (double) flowControl.inflight());
			swarm.registerSensor("hitRate", () -> org.genfork.grid.replication.metrics.ReplicationMetrics.mapHitRate());
			swarm.registerSensor("applyLag", () -> (double) nodeState.maxApplyLag(opLog));
			swarm.registerSensor("rttMs", () -> (double) nettyTransport.emaRttMs());
			swarm.registerSensor("openTx", this::openTxPressure);
			swarm.registerSensor("heapPressure", () -> {
				final Runtime rt = Runtime.getRuntime();
				final long used = rt.totalMemory() - rt.freeMemory();
				return (double) used / (double) rt.maxMemory();
			});
		}
		this.sparseCatchUp = new SparseCatchUp(nodeState, opLog, orchidNode);
		this.regionClaimService = new RegionClaimService(enabled, peerTransportEnabled, crossDcEnabled, writeAdmission, replicaReadsEnabled, maxStaleLag, nodeState, opLog, orchidNode, nettyTransport, regionCoordinator);
		this.peerMembershipService = new PeerMembershipService(enabled, crossDcEnabled, repairEnabled, nodeState, opLog, orchidNode, nettyTransport, sparseCatchUp, homologousRepair, publisher, crossDcPublisher, peers, regionClaimService);
		this.txMarkerService = new TxMarkerService(enabled, crossDcEnabled, nodeState, opLog, streamOpLogAppender, orchidNode, publisher, crossDcPublisher, homologousRepair);
		this.sealedHydrateService = new SealedHydrateService(enabled, hydrateMode, workingSetMaxEntries, adaptiveDiskFirstController, nodeState, opLog, snapshotService, indexCheckpointService, sealedGridMapService, homologousRepair, appliers);
		publisher.setPeers(peers);
		crossDcPublisher.setRemotePeers(peers);
		if (peerTransportEnabled) {
			publisher.setShipper(peerMembershipService::shipSameDc);
			crossDcPublisher.setShipper(peerMembershipService::shipCrossDc);
		} else {
			publisher.setShipper((peer, segment) -> {
			});
			crossDcPublisher.setShipper((peer, segment) -> {
			});
		}
		nettyTransport.bindHandlers(() -> orchidNode, domain -> appliers.get(domain), crossDcEnabled ? crossDcPublisher : null, nodeState, homologousRepair, opLog, sparseCatchUp, peerMembershipService::onPeerDiscovered, peerMembershipService::maybePromoteVoter, regionClaimService::regionEpoch, regionClaimService::regionRoleWire);
		nettyTransport.setSealedShardPackHandler(sealedHydrateService::onSealedShardPack);
		nettyTransport.setRegionClaimHandlers(regionClaimService::onRegionClaimReqBuildAck, regionClaimService::onRegionClaimAck);
		regionClaimService.seedObservedState();
	}

	public void start() {
		if (!enabled) {
			return;
		}
		if (peerTransportEnabled) {
			nettyTransport.start();
		}
		orchidNode.start();
		if (peerTransportEnabled && crossDcEnabled) {
			crossDcPublisher.start();
		}
		for (String domain : appliers.keySet()) {
			pipelineSchemaBarrier(domain);
		}
		backgroundScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			final Thread t = new Thread(r, "repl-bg");
			t.setDaemon(true);
			return t;
		});
		// Flush partial ship buffers so ops are not stuck until segmentSize is reached.
		backgroundScheduler.scheduleAtFixedRate(() -> {
			try {
				publisher.flushAll();
			} catch (RuntimeException ex) {
				log.warn("Publisher flush failed: {}", ex.toString());
			}
		}, 50, 50, TimeUnit.MILLISECONDS);
		backgroundScheduler.scheduleAtFixedRate(regionClaimService::pollPromotionState, PROMOTION_STATE_POLL_INTERVAL_MS, PROMOTION_STATE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
		// Schema barriers require ORCHID sync + phase-ranked proposer (defer on cold start / replica).
		backgroundScheduler.scheduleAtFixedRate(this::flushPendingSchemaBarriers, 200, 500, TimeUnit.MILLISECONDS);
		// Periodic sealed GridMap checkpoint (skip under OpLog fsync pressure).
		backgroundScheduler.scheduleAtFixedRate(sealedHydrateService::maybeDumpSealedAllDomains, SealedHydrateService.SEALED_DUMP_INTERVAL_MS, SealedHydrateService.SEALED_DUMP_INTERVAL_MS, TimeUnit.MILLISECONDS);
		if (peerTransportEnabled && swarmEnabled) {
			backgroundScheduler.scheduleAtFixedRate(this::swarmTick, swarm.scoreWindowMs(), swarm.scoreWindowMs(), TimeUnit.MILLISECONDS);
		}
		if (peerTransportEnabled && repairEnabled && homologousRepair != null && !peers.isEmpty()) {
			backgroundScheduler.scheduleAtFixedRate(this::repairTick, repairIntervalMs, repairIntervalMs, TimeUnit.MILLISECONDS);
		}
		if (adaptiveDiskFirstController != null) {
			adaptiveDiskFirstController.start();
		}
		registerOplogShutdownHook();
		log.info("ReplicationCoordinator started durable={} peerTransport={} consensus=ORCHID node={} adaptiveDiskFirst={}", true, peerTransportEnabled, nodeState.getNodeId(), adaptiveDiskFirstController != null);
	}

	/**
	 * Belts-and-suspenders for SIGTERM: Spring {@code destroyMethod=stop} usually runs first;
	 * this hook still flushes OpLog if the process exits without a clean Bean destroy.
	 * SIGKILL / SIGSEGV never run hooks — torn-tail replay on next open is the insurance.
	 */
	private void registerOplogShutdownHook() {
		final Thread hook = new Thread(this::stop, OPLOG_SHUTDOWN_HOOK_NAME);
		hook.setDaemon(false);
		oplogShutdownHook = hook;
		try {
			Runtime.getRuntime().addShutdownHook(hook);
		} catch (IllegalStateException ignored) {
		}
		// JVM already shutting down
	}

	private void unregisterOplogShutdownHook() {
		final Thread hook = oplogShutdownHook;
		oplogShutdownHook = null;
		if (hook == null) {
			return;
		}
		try {
			Runtime.getRuntime().removeShutdownHook(hook);
		} catch (IllegalStateException ignored) {
		}
		// Shutdown in progress — hook is executing or already executed
	}

	public void stop() {
		if (!enabled) {
			return;
		}
		if (!stopped.compareAndSet(false, true)) {
			return;
		}
		unregisterOplogShutdownHook();
		if (backgroundScheduler != null) {
			backgroundScheduler.shutdownNow();
		}
		if (adaptiveDiskFirstController != null) {
			adaptiveDiskFirstController.stop();
		}
		orchidNode.stop();
		if (peerTransportEnabled && crossDcEnabled) {
			crossDcPublisher.stop();
		}
		if (homologousRepair != null) {
			homologousRepair.flushLocusPersists();
		}
		if (peerTransportEnabled) {
			nettyTransport.close();
		}
		try {
			opLog.close();
		} catch (Exception ignored) {
		}
		if (sealedGridMapService != null) {
			sealedGridMapService.closeAll();
		}
		if (regionCoordinator != null) {
			try {
				regionCoordinator.close();
			} catch (Exception ignored) {
			}
		}
	}

	public void ensureOrchidSynced() {
		if (!enabled) {
			return;
		}
		ReplicaAccessGate.ensureWrite(this);
	}

	public MutationRecorder registerDomain(String domainType, Function<Integer, GridEntriesProcessor> processorByShard, boolean applyRemoteToLocalMap) {
		if (!enabled) {
			return null;
		}
		final ApplyAckSender ackSender = new ApplyAckSender() {
			@Override
			public void sendAck(ReplicationOp op) {
				if (peerTransportEnabled) {
					nettyTransport.broadcastApplyAck(op);
				}
			}
			@Override
			public void sendNack(ReplicationOp op, String reason) {
				if (!peerTransportEnabled) {
					return;
				}
				for (ReplicationPeer peer : peers) {
					nettyTransport.sendApplyNack(peer.id(), op, reason);
				}
			}
		};
		final ReplicaApplier applier = new ReplicaApplier(nodeState, opLog, processorByShard, ackSender, applyRemoteToLocalMap);
		applier.setEnvelopeCoordinator(txEnvelopeCoordinator);
		if (CATALOG_DOMAIN.equals(domainType) && catalogDdlHandler != null) {
			applier.setCatalogDdlHandler(catalogDdlHandler);
		}
		appliers.put(domainType, applier);
		if (processorByShard != null) {
			domainProcessors.put(domainType, processorByShard);
		}
		orchidNode.addApplyListener(op -> {
			if (op != null && domainType.equals(op.domainType())) {
				if (homologousRepair != null) {
					homologousRepair.observe(op);
				}
				// fromConsensus=true: OpLog + confirmPersisted remain in MutationRecorder.
				applier.apply(op, true);
			}
		});
		final MutationRecorder recorder = new MutationRecorder(nodeState, opLog, domainType, publisher, crossDcEnabled ? crossDcPublisher : null, homologousRepair, streamOpLogAppender);
		recorders.put(domainType, recorder);
		sealedGridMapService.bindProcessor(domainType, processorByShard);
		final boolean lazy = sealedHydrateService.isLazyHydrate() && !CATALOG_DOMAIN.equals(domainType);
		if (lazy) {
			log.info("Domain={} hydrateMode=LAZY (shard-touch on access)", domainType);
		} else {
			sealedHydrateService.hydrateDomainFull(domainType, applier);
		}
		long maxSeq = 0L;
		for (String streamKey : opLog.streamKeys()) {
			if (!OpLogStreamKeyUtil.startsWithDomain(streamKey, domainType)) {
				continue;
			}
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			maxSeq = Math.max(maxSeq, opLog.lastSeq(domainType, parsed.shard()));
		}
		sealedHydrateService.restoreOrMarkIndexCheckpoint(domainType, maxSeq);
		if (shardMigrator != null) {
			for (String streamKey : opLog.streamKeys()) {
				if (!OpLogStreamKeyUtil.startsWithDomain(streamKey, domainType)) {
					continue;
				}
				final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
				if (parsed == null) {
					continue;
				}
				final int shard = parsed.shard();
				if (shardMigrator.getPlacementMap().owner(domainType, shard) == null) {
					shardMigrator.getPlacementMap().setOwner(domainType, shard, nodeState.getNodeId());
				}
			}
		}
		pipelineSchemaBarrier(domainType);
		return recorder;
	}

	/**
	 * LAZY hydrate: load one shard into the local map on first touch.
	 * No-op when durability off or hydrateMode=FULL (already loaded).
	 */
	public void ensureShardHydrated(String domainType, int shard) {
		if (sealedHydrateService != null) {
			sealedHydrateService.ensureShardHydrated(domainType, shard);
		}
	}

	public boolean isLazyHydrate() {
		return sealedHydrateService != null && sealedHydrateService.isLazyHydrate();
	}

	public int workingSetMaxEntries() {
		return sealedHydrateService == null ? 0 : sealedHydrateService.workingSetMaxEntries();
	}

	public boolean isAdaptiveDiskFirst() {
		return sealedHydrateService != null && sealedHydrateService.isAdaptiveDiskFirst();
	}

	public AdaptiveDiskFirstController adaptiveDiskFirstController() {
		return sealedHydrateService == null ? null : sealedHydrateService.adaptiveDiskFirstController();
	}

	/**
	 * Create (or size) a working-set budget for a table and register it with adaptive control.
	 * Returns {@code null} when neither adaptive nor an explicit {@code workingSetMaxEntries} cap applies.
	 */
	public WorkingSetBudget createWorkingSetBudget() {
		return sealedHydrateService == null ? null : sealedHydrateService.createWorkingSetBudget();
	}

	/**
	 * Append schema BARRIER for each known shard of the domain when last op is not already a barrier
	 * for the current schemaEpoch (auto pipeline on domain register / epoch change).
	 * Deferred until this node is ORCHID-synced and phase-ranked proposer (replicas / cold start skip).
	 */
	public void pipelineSchemaBarrier(String domainType) {
		if (!enabled || domainType == null || orchidNode == null || !orchidNode.isRunning()) {
			return;
		}
		if (!orchidNode.isSynced() || !orchidNode.isPhaseRankedProposer()) {
			pendingSchemaBarriers.add(domainType);
			log.debug("Defer schema barrier domain={} synced={} proposer={}", domainType, orchidNode.isSynced(), orchidNode.isPhaseRankedProposer());
			return;
		}
		emitSchemaBarriers(domainType);
	}

	private void flushPendingSchemaBarriers() {
		if (!enabled || orchidNode == null || !orchidNode.isSynced() || !orchidNode.isPhaseRankedProposer()) {
			return;
		}
		for (String domain : List.copyOf(pendingSchemaBarriers)) {
			try {
				emitSchemaBarriers(domain);
				pendingSchemaBarriers.remove(domain);
			} catch (RuntimeException ex) {
				log.warn("Deferred schema barrier failed domain={}: {}", domain, ex.toString());
			}
		}
	}

	private void emitSchemaBarriers(String domainType) {
		final byte[] layout = domainType.getBytes(StandardCharsets.UTF_8);
		for (String streamKey : opLog.streamKeys()) {
			if (!OpLogStreamKeyUtil.startsWithDomain(streamKey, domainType)) {
				continue;
			}
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final int shard = parsed.shard();
			final long last = opLog.lastSeq(domainType, shard);
			if (last <= 0) {
				continue;
			}
			final List<ReplicationOp> tail = opLog.readFrom(domainType, shard, last, 1);
			if (!tail.isEmpty()) {
				final ReplicationOp op = tail.getFirst();
				if (op.type() == ReplicationOpType.BARRIER && op.schemaEpoch() == nodeState.getSchemaEpoch()) {
					continue;
				}
			}
			try {
				installSchemaBarrier(domainType, shard, layout);
			} catch (OrchidNotSyncedException ex) {
				pendingSchemaBarriers.add(domainType);
				log.debug("Schema barrier deferred again domain={} shard={}: {}", domainType, shard, ex.getMessage());
				return;
			}
		}
		pendingSchemaBarriers.remove(domainType);
	}

	/**
	 * Bump schema epoch and emit barriers for all registered domains.
	 */
	public void bumpSchemaEpoch(long newEpoch) {
		if (!enabled || newEpoch <= nodeState.getSchemaEpoch()) {
			return;
		}
		nodeState.setSchemaEpoch(newEpoch);
		nettyTransport.setSchemaEpoch(newEpoch);
		for (String domain : appliers.keySet()) {
			pipelineSchemaBarrier(domain);
		}
	}

	public synchronized void addPeer(ReplicationPeer peer) {
		if (peerMembershipService != null) {
			peerMembershipService.addPeer(peer);
		}
	}

	public synchronized void removePeer(String peerId) {
		if (peerMembershipService != null) {
			peerMembershipService.removePeer(peerId);
		}
	}

	public synchronized void isolatePeer(String peerId) {
		if (peerMembershipService != null) {
			peerMembershipService.isolatePeer(peerId);
		}
	}

	public synchronized void reconnectPeer(String peerId) {
		if (peerMembershipService != null) {
			peerMembershipService.reconnectPeer(peerId);
		}
	}

	public ReplicaApplier applier(String domainType) {
		return appliers.get(domainType);
	}

	public CompletableFuture<Void> awaitApplied(String domainType, int shard, long seq) {
		final ReplicaApplier applier = appliers.get(domainType);
		if (applier == null) {
			return CompletableFuture.completedFuture(null);
		}
		return applier.awaitApplied(domainType, shard, seq);
	}

	public void truncateSafe(String domainType, int shard) {
		final long applied = nodeState.appliedWatermark(domainType, shard);
		final long peerMin = nodeState.minPeerAck(domainType, shard);
		final long safe = peerMin == 0 ? applied : Math.min(applied, peerMin);
		if (safe > 0) {
			// Archive sync on caller thread; truncateSafe must not run on Netty EL.
			OpLogArchiveUtil.archiveBeforeTruncate(opLog, opLogArchiveRoot, domainType, shard, safe);
			if (opLogArchiveStreamRoot != null) {
				OpLogArchiveStreamer.shipCatchUp(opLog, opLogArchiveStreamRoot, domainType, shard);
			}
			opLog.truncateTo(domainType, shard, safe);
		}
	}

	public OpLogSegment installSnapshot(String domainType, int shard) {
		return sealedHydrateService.installSnapshot(domainType, shard);
	}

	/**
	 * Compact local map dump for domain (accelerates hydrate).
	 */
	public int dumpDomainSnapshot(String domainType) {
		return sealedHydrateService == null ? 0 : sealedHydrateService.dumpDomainSnapshot(domainType);
	}

	/**
	 * DROP TABLE: clear hydrate memo and relax adaptive mode for {@code domainType}.
	 * <p>
	 * Does <strong>not</strong> delete sealed {@code .gmap}/{@code .sbpt} or index-ckpt files on
	 * the DROP hot path — full {@link SealedGridMapService#deleteDomain} during JMeter/catalog
	 * DROP/CREATE crushed Capacity READ_ONLY (~10 k vs ~55 k) even when AdaptiveDiskFirst
	 * no longer enters HIGH on sealed-miss bursts. File retire remains available via
	 * {@link SealedGridMapService#deleteDomain} / {@link IndexCheckpointService#deleteDomain}
	 * for explicit domain teardown outside the load DROP cycle.
	 */
	public void purgeDomainArtifacts(String domainType) {
		if (!enabled || domainType == null || domainType.isEmpty()) {
			return;
		}
		if (sealedHydrateService != null) {
			sealedHydrateService.forgetDomain(domainType);
		}
		if (adaptiveDiskFirstController != null) {
			adaptiveDiskFirstController.relaxAfterDomainPurge();
		}
	}

	/**
	 * Synced and phase-ranked proposer — may accept writes (requires write-admission + region Active).
	 */
	public boolean isWriterEligible() {
		return regionClaimService != null && regionClaimService.isWriterEligible();
	}

	public boolean regionAllowsWrites() {
		return regionClaimService == null || regionClaimService.regionAllowsWrites();
	}

	public long regionEpoch() {
		return regionClaimService == null ? 0L : regionClaimService.regionEpoch();
	}

	public byte regionRoleWire() {
		return regionClaimService == null ? (byte) 0 : regionClaimService.regionRoleWire();
	}

	public boolean observePeerRegionEpoch(long peerEpoch, String peerNodeId) {
		return regionClaimService != null && regionClaimService.observePeerRegionEpoch(peerEpoch, peerNodeId);
	}

	public boolean noteRemotePeerRegion(long peerEpoch, byte peerRoleWire) {
		return regionClaimService != null && regionClaimService.noteRemotePeerRegion(peerEpoch, peerRoleWire);
	}

	public boolean tryRegionClaim(int ackCount) {
		return regionClaimService != null && regionClaimService.tryRegionClaim(ackCount);
	}

	public void addPromotionListener(Runnable listener) {
		if (regionClaimService != null) {
			regionClaimService.addPromotionListener(listener);
		}
	}

	public void removePromotionListener(Runnable listener) {
		if (regionClaimService != null) {
			regionClaimService.removePromotionListener(listener);
		}
	}

	public boolean isWriteAdmission() {
		return regionClaimService == null || regionClaimService.isWriteAdmission();
	}

	public boolean hasActiveRemoteDcLink() {
		return regionClaimService == null || regionClaimService.hasActiveRemoteDcLink();
	}

	public MutationRecorder mutationRecorder(String domainType) {
		return recorders.get(domainType);
	}

	public String promoteHint() {
		return regionClaimService == null ? null : regionClaimService.promoteHint();
	}

	public long maxStaleLag() {
		return regionClaimService == null ? Long.MAX_VALUE : regionClaimService.maxStaleLag();
	}

	public boolean isApplyLagStale() {
		return regionClaimService != null && regionClaimService.isApplyLagStale();
	}

	public boolean isReplicaReadsEnabled() {
		return regionClaimService != null && regionClaimService.isReplicaReadsEnabled();
	}

	public boolean regionAllowsReplicaReads() {
		return regionClaimService == null || regionClaimService.regionAllowsReplicaReads();
	}

	public int installFromOpLog(String domainType, int shard, long fromSeqInclusive, int limit) {
		return sealedHydrateService.installFromOpLog(domainType, shard, fromSeqInclusive, limit);
	}

	public void recordTxMarker(ReplicationOpType type, long txId, String domainType, int shard) {
		if (txMarkerService != null) {
			txMarkerService.recordTxMarker(type, txId, domainType, shard);
		}
	}

	public void recordTxMarker(ReplicationOpType type, long txId, String domainType, int shard, byte[] value) {
		if (txMarkerService != null) {
			txMarkerService.recordTxMarker(type, txId, domainType, shard, value);
		}
	}

	public void recordTxMarkersBatch(ReplicationOpType type, long txId, List<TxEnvelopeCodec.StreamRef> streams, byte[] value) {
		if (txMarkerService != null) {
			txMarkerService.recordTxMarkersBatch(type, txId, streams, value);
		}
	}

	/**
	 * Proposer path: ORCHID-commit catalog DDL, persist OpLog, ship to peers (fail-closed).
	 * {@code schemaEpoch} is the catalog layout epoch for this DDL statement.
	 */
	public void recordDdl(String ddlSql, long schemaEpoch) {
		if (!enabled || orchidNode == null) {
			return;
		}
		if (ddlSql == null || ddlSql.isBlank()) {
			throw new IllegalArgumentException("DDL SQL is empty");
		}
		if (catalogDdlHandler == null || !appliers.containsKey(CATALOG_DOMAIN)) {
			throw new IllegalStateException("SQL catalog not bound; call bindSqlCatalog first");
		}
		ensureOrchidSynced();
		final byte[] key = "ddl".getBytes(StandardCharsets.UTF_8);
		final byte[] value = ddlSql.trim().getBytes(StandardCharsets.UTF_8);
		final long epoch = schemaEpoch <= 0L ? nodeState.getSchemaEpoch() : schemaEpoch;
		final long opSeqHint = Math.max(1L, opLog.lastSeq(CATALOG_DOMAIN, CATALOG_SHARD) + 1);
		final ReplicationOp raw = OpLogCodec.withChecksum(new ReplicationOp(CATALOG_DOMAIN, CATALOG_SHARD, opSeqHint, ReplicationOpType.DDL, key, value, epoch, 0L));
		try {
			final long seq = orchidNode.appendAndWaitCommit(raw).join();
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(CATALOG_DOMAIN, CATALOG_SHARD, seq, ReplicationOpType.DDL, key, value, epoch, 0L));
			opLog.append(committed);
			orchidNode.confirmPersisted(seq);
			nodeState.advanceApplied(CATALOG_DOMAIN, CATALOG_SHARD, seq);
			if (homologousRepair != null) {
				homologousRepair.observe(committed);
			}
			if (publisher != null) {
				publisher.onAppended(committed);
			}
			if (crossDcEnabled && crossDcPublisher != null) {
				crossDcPublisher.onAppended(committed);
			}
		} catch (CompletionException ex) {
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof OrchidNotSyncedException orchidEx) {
				throw orchidEx;
			}
			throw new IllegalStateException("DDL orchid commit failed", cause);
		}
	}

	public OpLogSegment installSchemaBarrier(String domainType, int shard, byte[] layoutHash) {
		long hash = 0L;
		if (layoutHash != null) {
			for (byte b : layoutHash) {
				hash = 31L * hash + (b & 255);
			}
		}
		final byte[] key = SchemaEpochSupport.layoutHashBytes(hash);
		final ReplicationOp raw = OpLogCodec.withChecksum(new ReplicationOp(domainType, shard, Math.max(1L, opLog.lastSeq(domainType, shard) + 1), ReplicationOpType.BARRIER, key, null, nodeState.getSchemaEpoch(), 0L));
		if (streamOpLogAppender == null) {
			return installSchemaBarrierDirect(domainType, shard, key, raw);
		}
		final OrchidNode.AdmittedPropose admitted = orchidNode.appendAndAdmit(raw, expected -> {
			if (expected > 0L) {
				streamOpLogAppender.beginJoin(domainType, shard, expected);
			}
		});
		boolean joined = false;
		try {
			final long seq = admitted.future().join();
			joined = true;
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(domainType, shard, seq, ReplicationOpType.BARRIER, key, null, nodeState.getSchemaEpoch(), 0L));
			streamOpLogAppender.publishCommitted(committed);
			streamOpLogAppender.force(domainType, shard);
			orchidNode.confirmPersisted(seq);
			nodeState.advanceApplied(domainType, shard, seq);
			if (homologousRepair != null) {
				homologousRepair.observe(committed);
			}
			return new OpLogSegment(domainType, shard, seq, seq, List.of(committed), OpLogCodec.segmentChecksum(List.of(committed)));
		} catch (CompletionException ex) {
			if (!joined) {
				streamOpLogAppender.cancelJoin(domainType, shard, admitted.expectedOpSeq());
			}
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof OrchidNotSyncedException orchidEx) {
				throw orchidEx;
			}
			throw new IllegalStateException("Schema barrier orchid commit failed", cause);
		} catch (RuntimeException ex) {
			if (!joined) {
				streamOpLogAppender.cancelJoin(domainType, shard, admitted.expectedOpSeq());
			}
			throw ex;
		}
	}

	/**
	 * Durability-off / bootstrap path: direct OpLog append (no concurrent holdback).
	 */
	private OpLogSegment installSchemaBarrierDirect(String domainType, int shard, byte[] key, ReplicationOp raw) {
		try {
			final long seq = orchidNode.appendAndWaitCommit(raw).join();
			final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(domainType, shard, seq, ReplicationOpType.BARRIER, key, null, nodeState.getSchemaEpoch(), 0L));
			opLog.append(committed);
			orchidNode.confirmPersisted(seq);
			nodeState.advanceApplied(domainType, shard, seq);
			if (homologousRepair != null) {
				homologousRepair.observe(committed);
			}
			return new OpLogSegment(domainType, shard, seq, seq, List.of(committed), OpLogCodec.segmentChecksum(List.of(committed)));
		} catch (CompletionException ex) {
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof OrchidNotSyncedException orchidEx) {
				throw orchidEx;
			}
			throw new IllegalStateException("Schema barrier orchid commit failed", cause);
		}
	}

	private void swarmTick() {
		swarm.tick();
		if (shardMigrator != null) {
			shardMigrator.getCooldown().tick();
		}
		if (peers.isEmpty() || opLog.streamKeys().isEmpty()) {
			return;
		}
		final PlacementTopology topology = buildPlacementTopology();
		final PlacementPlan plan = swarm.optimizePlacement(topology);
		if (plan != null && plan.voterSet() != null && plan.voterSet().hasRecommendation()) {
			swarm.getLastVoterRecommendation().set(plan.voterSet());
			applyVoterSetHintIfEnabled(Set.copyOf(plan.voterSet().recommendedRemoteVoters()));
		}
		if (plan == null || !plan.hasMigrations()) {
			return;
		}
		if (!applyAutoCutover) {
			return;
		}
		// Writer-only migrate: learners/replicas keep sensors+hints; avoid dual-map ping-pong.
		if (!isWriterEligible()) {
			return;
		}
		// Capacity path: do not ship sealed/OpLog cutover while local admission is saturated.
		if (!swarm.isMigrateIoAllowed()) {
			return;
		}
		for (ShardMigrateAction action : plan.migrations()) {
			if (isOverlayBlockingShardMigrate(action.domainType(), action.shard())) {
				continue;
			}
			if (hasOpenTxOnStream(action.domainType(), action.shard())) {
				continue;
			}
			if (shardMigrator.getCooldown().isCooling(action.domainType(), action.shard())) {
				continue;
			}
			final String affinity = shardMigrator.getPlacementMap().affinity(action.domainType(), action.shard());
			if (affinity != null && affinity.equals(nodeState.getNodeId())) {
				continue;
			}
			final String target = affinity != null ? affinity : action.targetPeerId();
			if (target == null || target.equals(nodeState.getNodeId())) {
				continue;
			}
			// Fail-closed OpLog cutover: migrateRange ships OpLog range then cutover ownership.
			final long from = Math.max(1L, nodeState.peerAck(target, action.domainType(), action.shard()) + 1);
			final int batch = SwarmMigrateBatchSizes.forApplyLag(nodeState.maxApplyLag(opLog));
			shardMigrator.migrateRange(target, action.domainType(), action.shard(), from, batch);
		}
	}

	/**
	 * Whether swarmTick may call {@link ShardMigrator#migrateRange} ({@code apply-auto-cutover}).
	 * Visible for IT.
	 */
	boolean isApplyAutoCutover() {
		return applyAutoCutover;
	}

	/**
	 * Gated apply of remote digest voter hints ({@code apply-voter-set-hints}). Visible for IT.
	 */
	void applyVoterSetHintIfEnabled(Set<String> recommended) {
		if (!applyVoterSetHints || orchidNode == null || recommended == null) {
			return;
		}
		final Set<String> current = orchidNode.multiDcConfig().remoteVoterIds();
		if (!recommended.equals(current)) {
			log.info("Applying voter-set hint remoteVoters={} (was {})", recommended, current);
			orchidNode.applyRemoteVoters(recommended);
		}
	}

	private PlacementTopology buildPlacementTopology() {
		final List<PlacementTopology.PeerEndpoint> peerEndpoints = new ArrayList<>(peers.size());
		final Set<String> configuredRemoteVoters = orchidNode == null ? Set.of() : orchidNode.multiDcConfig().remoteVoterIds();
		for (ReplicationPeer peer : peers) {
			final PeerRole role = configuredRemoteVoters.contains(peer.id()) ? PeerRole.VOTER : PeerRole.LEARNER;
			peerEndpoints.add(new PlacementTopology.PeerEndpoint(peer.id(), peer.dc(), role));
		}
		final List<PlacementTopology.StreamPlacement> streams = new ArrayList<>();
		for (String streamKey : opLog.streamKeys()) {
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final String domain = parsed.domain();
			final int shard = parsed.shard();
			final String owner = shardMigrator.getPlacementMap().owner(domain, shard);
			final String affinity = shardMigrator.getPlacementMap().affinity(domain, shard);
			final long last = opLog.lastSeq(domain, shard);
			double lagHint = 0;
			for (ReplicationPeer peer : peers) {
				lagHint = Math.max(lagHint, Math.max(0, last - nodeState.peerAck(peer.id(), domain, shard)));
			}
			streams.add(new PlacementTopology.StreamPlacement(domain, shard, owner, affinity, lagHint));
		}
		return new PlacementTopology(nodeState.getNodeId(), nodeState.getLocalDc(), List.copyOf(peerEndpoints), List.copyOf(streams));
	}

	private static GridConfigurationProperties.PlacementOptimizerProps resolvePlacementOptimizer(GridConfigurationProperties.ReplicationProps cfg) {
		final GridConfigurationProperties.PlacementOptimizerProps props = cfg.getPlacementOptimizer();
		return props != null ? props : new GridConfigurationProperties.PlacementOptimizerProps();
	}

	/**
	 * Resolve PITR archive root from durability props; {@code null} when disabled.
	 */
	private static Path resolveOpLogArchiveRoot(GridConfigurationProperties.DurabilityProps durability) {
		if (durability == null) {
			return null;
		}
		final GridConfigurationProperties.OpLogArchiveProps archive = durability.getOpLogArchive();
		if (archive == null || !archive.isEnabled()) {
			return null;
		}
		final String dir = archive.getDir();
		if (dir == null || dir.isBlank()) {
			return Path.of(DEFAULT_OPLOG_ARCHIVE_DIR);
		}
		return Path.of(dir);
	}

	/**
	 * Resolve append-only off-node stream root; {@code null} when stream disabled.
	 */
	private static Path resolveOpLogArchiveStreamRoot(GridConfigurationProperties.DurabilityProps durability, Path archiveRoot) {
		if (durability == null) {
			return null;
		}
		final GridConfigurationProperties.OpLogArchiveProps archive = durability.getOpLogArchive();
		if (archive == null || !archive.isStreamEnabled()) {
			return null;
		}
		final String streamDir = archive.getStreamDir();
		if (streamDir != null && !streamDir.isBlank()) {
			return Path.of(streamDir);
		}
		if (archiveRoot != null) {
			return OpLogArchiveStreamer.defaultStreamRoot(archiveRoot);
		}
		final String dir = archive.getDir();
		final Path base = dir == null || dir.isBlank() ? Path.of(DEFAULT_OPLOG_ARCHIVE_DIR) : Path.of(dir);
		return OpLogArchiveStreamer.defaultStreamRoot(base);
	}

	private void repairTick() {
		if (homologousRepair == null || peers.isEmpty()) {
			return;
		}
		final long tick = repairTickCount.incrementAndGet();
		final boolean fullReconcile = (tick % REPAIR_FULL_RECONCILE_EVERY) == 0L;
		for (ReplicationPeer peer : peers) {
			for (String streamKey : opLog.streamKeys()) {
				final int hash = streamKey.lastIndexOf('#');
				if (hash <= 0) {
					continue;
				}
				final String domain = streamKey.substring(0, hash);
				final int shard = Integer.parseInt(streamKey.substring(hash + 1));
				if (hasOpenTxOnStream(domain, shard)) {
					continue;
				}
				if (!fullReconcile) {
					final long lastSeq = opLog.lastSeq(domain, shard);
					final long peerAck = nodeState.peerAck(peer.id(), domain, shard);
					// Caught up or soft in-flight lag: skip O(keys) locus encode/ship.
					// Checksum heal remains on full cadence; OpLog publisher streams catch-up.
					if (lastSeq > 0L) {
						final long lag = lastSeq - peerAck;
						if (lag <= 0L || lag < REPAIR_SOFT_LAG_OPS) {
							continue;
						}
					}
				}
				nettyTransport.sendRepairRequest(peer.id(), domain, shard, homologousRepair.getLocusMap().snapshot(domain, shard));
			}
		}
	}

	/**
	 * Local applier or repair locus still inside an open TX unit on this stream.
	 */
	private boolean hasOpenTxOnStream(String domainType, int shard) {
		if (homologousRepair != null && homologousRepair.hasOpenTx(domainType, shard)) {
			return true;
		}
		final ReplicaApplier applier = appliers.get(domainType);
		return applier != null && applier.hasOpenTx(domainType, shard);
	}

	/**
	 * Sensor: count of streams with open TX (applier staging or repair locus).
	 */
	private double openTxPressure() {
		int n = 0;
		for (String streamKey : opLog.streamKeys()) {
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final String domain = parsed.domain();
			final int shard = parsed.shard();
			if (hasOpenTxOnStream(domain, shard)) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Remote sync voters for {@link CrossDcMode#SYNC_VOTERS_ACROSS_DC}: explicit {@code voters}
	 * list, else all remote-DC peers except {@code learners}.
	 */
	public static Set<String> resolveRemoteVoters(CrossDcMode mode, GridConfigurationProperties.CrossDcProps crossDc, List<ReplicationPeer> peers, String localDc) {
		return CrossDcVoterUtil.resolveRemoteVoters(mode, crossDc, peers, localDc);
	}

	public ReplicationNodeState getNodeState() {
		return this.nodeState;
	}

	public OpLog getOpLog() {
		return this.opLog;
	}

	public ReplicationPublisher getPublisher() {
		return this.publisher;
	}

	public CrossDcPublisher getCrossDcPublisher() {
		return this.crossDcPublisher;
	}

	public TxEnvelopeCoordinator getTxEnvelopeCoordinator() {
		return this.txEnvelopeCoordinator;
	}

	public SnapshotService getSnapshotService() {
		return this.snapshotService;
	}

	public IndexCheckpointService getIndexCheckpointService() {
		return this.indexCheckpointService;
	}

	public SealedGridMapService getSealedGridMapService() {
		return this.sealedGridMapService;
	}

	public OrchidNode getOrchidNode() {
		return this.orchidNode;
	}

	public SparseCatchUp getSparseCatchUp() {
		return this.sparseCatchUp;
	}

	public HomologousRepair getHomologousRepair() {
		return this.homologousRepair;
	}

	public AdaptiveReplicaSwarm getSwarm() {
		return this.swarm;
	}

	public ShardMigrator getShardMigrator() {
		return this.shardMigrator;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * Peer Netty ship / swarm / repair (independent of local durability).
	 */
	public boolean isPeerTransportEnabled() {
		return this.peerTransportEnabled;
	}

	public NettyReplicationTransport getNettyTransport() {
		return this.nettyTransport;
	}

	/**
	 * Serializes OpLog TX units per stream (prevents interleaved BEGIN under WRITE_BATCH).
	 */
	public StreamCommitSerializer getStreamCommitSerializer() {
		return this.streamCommitSerializer;
	}

	public RegionRoleCoordinator getRegionCoordinator() {
		return this.regionCoordinator;
	}
}
