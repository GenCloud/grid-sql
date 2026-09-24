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
package org.genfork.grid.context.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import org.genfork.grid.catalog.CatalogMetaCache;
import org.genfork.grid.replication.region.RegionRole;
import org.genfork.grid.sql.tx.SqlRecordLockManager;

/**
 * Grid Boot configuration properties.
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
@ConfigurationProperties(prefix = "grid")
public class GridConfigurationProperties {
	public static final int DEFAULT_SHARDS = 4;

	private ReplicationProps replication = new ReplicationProps();
	private DurabilityProps durability = new DurabilityProps();
	private CodecProps codec = new CodecProps();
	private OverlayProps overlay = new OverlayProps();
	private SqlProps sql = new SqlProps();
	private SqlServerProps sqlServer = new SqlServerProps();

	public ReplicationProps getReplication() {
		return replication;
	}

	public void setReplication(ReplicationProps replication) {
		this.replication = replication;
	}

	public DurabilityProps getDurability() {
		return durability;
	}

	public void setDurability(DurabilityProps durability) {
		this.durability = durability;
	}

	public CodecProps getCodec() {
		return codec;
	}

	public void setCodec(CodecProps codec) {
		this.codec = codec;
	}

	public OverlayProps getOverlay() {
		return overlay;
	}

	public void setOverlay(OverlayProps overlay) {
		this.overlay = overlay;
	}

	public SqlProps getSql() {
		return sql;
	}

	public void setSql(SqlProps sql) {
		this.sql = sql;
	}

	public SqlServerProps getSqlServer() {
		return sqlServer;
	}

	public void setSqlServer(SqlServerProps sqlServer) {
		this.sqlServer = sqlServer;
	}

	/**
	 * Local durability (ORCHID + OpLog + hydrate).
	 */
	public static class DurabilityProps {
		/**
		 * Local ORCHID + OpLog + hydrate (solo OK with empty peers).
		 * Independent of {@link ReplicationProps#enabled} (peer transport).
		 */
		private boolean enabled = true;
		/**
		 * {@code FULL} — load domain into map on register (default).
		 * {@code LAZY} — watermarks only; load on demand (see hydrate path).
		 */
		private String hydrateMode = "FULL";
		/**
		 * Max committed keys retained in RAM working set across shards (0 = unlimited when adaptive off;
		 * with adaptive disk-first, 0 uses internal default ceiling).
		 * Eviction reloads from sealed GridMap on miss.
		 */
		private int workingSetMaxEntries = 0;
		/**
		 * When true (default), auto-tune WS cap / LAZY semantics by heap+WS pressure.
		 * Effective only while durability is enabled. Manual {@link #workingSetMaxEntries} /
		 * {@link #hydrateMode} remain ceiling/floor overrides.
		 */
		private boolean adaptiveDiskFirst = true;
		/**
		 * Opt-in OpLog archive-before-truncate for PITR.
		 * YAML: {@code grid.durability.oplog-archive}.
		 */
		private OpLogArchiveProps opLogArchive = new OpLogArchiveProps();

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getHydrateMode() {
			return hydrateMode;
		}

		public void setHydrateMode(String hydrateMode) {
			this.hydrateMode = hydrateMode;
		}

		public int getWorkingSetMaxEntries() {
			return workingSetMaxEntries;
		}

		public void setWorkingSetMaxEntries(int workingSetMaxEntries) {
			this.workingSetMaxEntries = workingSetMaxEntries;
		}

		public boolean isAdaptiveDiskFirst() {
			return adaptiveDiskFirst;
		}

		public void setAdaptiveDiskFirst(boolean adaptiveDiskFirst) {
			this.adaptiveDiskFirst = adaptiveDiskFirst;
		}

		public OpLogArchiveProps getOpLogArchive() {
			return opLogArchive;
		}

		public void setOpLogArchive(OpLogArchiveProps opLogArchive) {
			this.opLogArchive = opLogArchive;
		}
	}

	/**
	 * PITR OpLog archive policy (default off — live truncate path pays no archive I/O).
	 */
	public static class OpLogArchiveProps {
		/**
		 * When true, copy OpLog {@code [prevW+1 … W]} under {@link #dir} before
		 * {@code OpLog.truncateTo}. Fail-closed: archive I/O failure aborts truncate.
		 */
		private boolean enabled = false;
		/** Archive root (per {@code domainHex_shard} subdirs). */
		private String dir = "./data/oplog-archive";
		/**
		 * When true, append-only ship OpLog ranges to {@link #streamDir} (off-node stream).
		 * YAML: {@code grid.durability.oplog-archive.stream-enabled}.
		 */
		private boolean streamEnabled = false;
		/**
		 * Off-node append stream root. Blank + {@link #streamEnabled} → {@code {dir}/stream}.
		 */
		private String streamDir = "";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getDir() {
			return dir;
		}

		public void setDir(String dir) {
			this.dir = dir;
		}

		public boolean isStreamEnabled() {
			return streamEnabled;
		}

		public void setStreamEnabled(boolean streamEnabled) {
			this.streamEnabled = streamEnabled;
		}

		public String getStreamDir() {
			return streamDir;
		}

		public void setStreamDir(String streamDir) {
			this.streamDir = streamDir;
		}
	}

	/**
	 * Peer replication transport and related sub-configs.
	 */
	public static class ReplicationProps {
		private boolean enabled;
		private String nodeId = "node-1";
		private String clusterId = "grid-default";
		private OrchidProps orchid = new OrchidProps();
		private RepairProps repair = new RepairProps();
		private SwarmProps swarm = new SwarmProps();
		/**
		 * Hierarchical multipopulation placement optimizer (HDCRM-class algorithm).
		 * YAML key: {@code grid.replication.placement-optimizer} — not {@code hdcrm.*}.
		 */
		private PlacementOptimizerProps placementOptimizer = new PlacementOptimizerProps();
		private TransportProps transport = new TransportProps();
		private CrossDcProps crossDc = new CrossDcProps();
		private OpLogProps opLog = new OpLogProps();
		private FlowProps flow = new FlowProps();
		private HaProps ha = new HaProps();
		/**
		 * Multi-DC Active/Hold/Witness region epoch fencing.
		 * YAML: {@code grid.replication.region}.
		 */
		private RegionProps region = new RegionProps();

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getNodeId() {
			return nodeId;
		}

		public void setNodeId(String nodeId) {
			this.nodeId = nodeId;
		}

		public String getClusterId() {
			return clusterId;
		}

		public void setClusterId(String clusterId) {
			this.clusterId = clusterId;
		}

		public OrchidProps getOrchid() {
			return orchid;
		}

		public void setOrchid(OrchidProps orchid) {
			this.orchid = orchid;
		}

		public RepairProps getRepair() {
			return repair;
		}

		public void setRepair(RepairProps repair) {
			this.repair = repair;
		}

		public SwarmProps getSwarm() {
			return swarm;
		}

		public void setSwarm(SwarmProps swarm) {
			this.swarm = swarm;
		}

		public PlacementOptimizerProps getPlacementOptimizer() {
			return placementOptimizer;
		}

		public void setPlacementOptimizer(PlacementOptimizerProps placementOptimizer) {
			this.placementOptimizer = placementOptimizer;
		}

		public TransportProps getTransport() {
			return transport;
		}

		public void setTransport(TransportProps transport) {
			this.transport = transport;
		}

		public CrossDcProps getCrossDc() {
			return crossDc;
		}

		public void setCrossDc(CrossDcProps crossDc) {
			this.crossDc = crossDc;
		}

		public OpLogProps getOpLog() {
			return opLog;
		}

		public void setOpLog(OpLogProps opLog) {
			this.opLog = opLog;
		}

		public FlowProps getFlow() {
			return flow;
		}

		public void setFlow(FlowProps flow) {
			this.flow = flow;
		}

		public HaProps getHa() {
			return ha;
		}

		public void setHa(HaProps ha) {
			this.ha = ha;
		}

		public RegionProps getRegion() {
			return region;
		}

		public void setRegion(RegionProps region) {
			this.region = region;
		}
	}

	/**
	 * Multi-DC region epoch fencing.
	 */
	public static class RegionProps {
		/**
		 * When true, persist region epoch/role and fence writes on epoch mismatch.
		 * Default false keeps 1-DC / legacy write-admission behavior.
		 */
		private boolean enabled;
		/**
		 * Bootstrap role: {@link RegionRole#ACTIVE}, {@link RegionRole#HOLD}, or {@link RegionRole#WITNESS}.
		 * YAML binds case-insensitively ({@code ACTIVE}/{@code HOLD}/{@code WITNESS}).
		 */
		private RegionRole role = RegionRole.ACTIVE;
		/** Initial epoch when no durable state exists (monotonic {@code >= 1}). */
		private long epoch = 1L;
		/** Silence timeout before Hold may attempt claim (ms). */
		private long claimTimeoutMs = 5_000L;
		/**
		 * Hold/Witness voter count for claim majority (self excluded from lone self-elect).
		 */
		private int quorumSize = 2;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public RegionRole getRole() {
			return role;
		}

		public void setRole(RegionRole role) {
			this.role = role;
		}

		public long getEpoch() {
			return epoch;
		}

		public void setEpoch(long epoch) {
			this.epoch = epoch;
		}

		public long getClaimTimeoutMs() {
			return claimTimeoutMs;
		}

		public void setClaimTimeoutMs(long claimTimeoutMs) {
			this.claimTimeoutMs = claimTimeoutMs;
		}

		public int getQuorumSize() {
			return quorumSize;
		}

		public void setQuorumSize(int quorumSize) {
			this.quorumSize = quorumSize;
		}
	}

	/**
	 * HA lag / replica-read gates.
	 */
	public static class HaProps {
		/**
		 * Max apply lag (ops) before reads are considered too stale for promote / stale-read gates.
		 * {@code Long.MAX_VALUE} disables the gate.
		 */
		private long maxStaleLag = 10_000L;
		/**
		 * When true, READ_REPLICA sessions may SELECT on synced voters / Hold (fail-closed on lag).
		 * Default false — linearizable PRIMARY path unchanged.
		 */
		private boolean replicaReadsEnabled = false;

		public long getMaxStaleLag() {
			return maxStaleLag;
		}

		public void setMaxStaleLag(long maxStaleLag) {
			this.maxStaleLag = maxStaleLag;
		}

		public boolean isReplicaReadsEnabled() {
			return replicaReadsEnabled;
		}

		public void setReplicaReadsEnabled(boolean replicaReadsEnabled) {
			this.replicaReadsEnabled = replicaReadsEnabled;
		}
	}

	/**
	 * ORCHID phase / digest quorum settings.
	 */
	public static class OrchidProps {
		/** Per-tick Kuramoto pull scale (applied as coupling/live * sinΔ * dt). */
		private double coupling = 15.0;
		/**
		 * Natural frequency in Hz. Keep ω·tick ≪ 1 so delayed peer phase samples remain meaningful
		 * (e.g. 1 Hz at tick 10ms → ~0.06 rad/tick). Values like 50 Hz with 10ms ticks prevent R→1.
		 */
		private double naturalFreqHz = 1.0;
		private double orderThreshold = 0.85;
		private long tickMs = 10;
		private String digestQuorum = "MAJORITY";
		/**
		 * Max concurrent ORCHID proposes (contiguous prevOpSeq pipeline). Default 64;
		 * set 1 for legacy single-flight behavior.
		 */
		private int maxProposeInFlight = 64;

		public double getCoupling() {
			return coupling;
		}

		public void setCoupling(double coupling) {
			this.coupling = coupling;
		}

		public double getNaturalFreqHz() {
			return naturalFreqHz;
		}

		public void setNaturalFreqHz(double naturalFreqHz) {
			this.naturalFreqHz = naturalFreqHz;
		}

		public double getOrderThreshold() {
			return orderThreshold;
		}

		public void setOrderThreshold(double orderThreshold) {
			this.orderThreshold = orderThreshold;
		}

		public long getTickMs() {
			return tickMs;
		}

		public void setTickMs(long tickMs) {
			this.tickMs = tickMs;
		}

		public String getDigestQuorum() {
			return digestQuorum;
		}

		public void setDigestQuorum(String digestQuorum) {
			this.digestQuorum = digestQuorum;
		}

		public int getMaxProposeInFlight() {
			return maxProposeInFlight;
		}

		public void setMaxProposeInFlight(int maxProposeInFlight) {
			this.maxProposeInFlight = maxProposeInFlight;
		}
	}

	/**
	 * Homologous repair settings.
	 */
	public static class RepairProps {
		private boolean homologousEnabled = true;
		private long reconcileIntervalMs = 5000;

		public boolean isHomologousEnabled() {
			return homologousEnabled;
		}

		public void setHomologousEnabled(boolean homologousEnabled) {
			this.homologousEnabled = homologousEnabled;
		}

		public long getReconcileIntervalMs() {
			return reconcileIntervalMs;
		}

		public void setReconcileIntervalMs(long reconcileIntervalMs) {
			this.reconcileIntervalMs = reconcileIntervalMs;
		}
	}

	/**
	 * Adaptive replica swarm / migrate thresholds.
	 */
	public static class SwarmProps {
		private boolean enabled = true;
		private long scoreWindowMs = 5000;
		private double migrateThreshold = 0.3;
		/**
		 * Enter migrate search when composite >= migrateThreshold + enterDelta (hysteresis).
		 * Default 0.25 → enter at 0.55 with threshold 0.3 (avoids READ-path search flap).
		 */
		private double migrateEnterDelta = 0.25;
		/**
		 * Stay armed until composite &lt; migrateThreshold - exitDelta; also optimizer search floor.
		 */
		private double migrateExitDelta = 0.05;
		/**
		 * Swarm ticks to suppress re-migrate of the same stream after cutover.
		 */
		private int cutoverCooldownTicks = 12;
		/**
		 * When true, {@code ReplicationCoordinator.swarmTick} invokes
		 * {@code ShardMigrator.migrateRange} for placement-plan migrations.
		 * Default <strong>true</strong> — production OpLog + sealed-pack cutover is on;
		 * set {@code false} to suppress migrate under load (hints / plans / voter-set still run).
		 * See {@code docs/en/bio-inspired.md} cutover runbook.
		 */
		private boolean applyAutoCutover = true;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public long getScoreWindowMs() {
			return scoreWindowMs;
		}

		public void setScoreWindowMs(long scoreWindowMs) {
			this.scoreWindowMs = scoreWindowMs;
		}

		public double getMigrateThreshold() {
			return migrateThreshold;
		}

		public void setMigrateThreshold(double migrateThreshold) {
			this.migrateThreshold = migrateThreshold;
		}

		public double getMigrateEnterDelta() {
			return migrateEnterDelta;
		}

		public void setMigrateEnterDelta(double migrateEnterDelta) {
			this.migrateEnterDelta = migrateEnterDelta;
		}

		public double getMigrateExitDelta() {
			return migrateExitDelta;
		}

		public void setMigrateExitDelta(double migrateExitDelta) {
			this.migrateExitDelta = migrateExitDelta;
		}

		public int getCutoverCooldownTicks() {
			return cutoverCooldownTicks;
		}

		public void setCutoverCooldownTicks(int cutoverCooldownTicks) {
			this.cutoverCooldownTicks = cutoverCooldownTicks;
		}

		public boolean isApplyAutoCutover() {
			return applyAutoCutover;
		}

		public void setApplyAutoCutover(boolean applyAutoCutover) {
			this.applyAutoCutover = applyAutoCutover;
		}
	}

	/**
	 * Public YAML: {@code grid.replication.placement-optimizer.*} (sibling of {@code swarm}).
	 */
	public static class PlacementOptimizerProps {
		/** When true, AdaptiveReplicaSwarm runs DC→node multipopulation search. */
		private boolean enabled = true;
		private int dcPopulationSize = 8;
		private int nodePopulationSize = 12;
		private int generationsPerTick = 3;
		private double mutationRate = 0.25;
		private double diversityWeight = 0.15;
		private int maxMigratesPerTick = 2;
		/** Fixed RNG seed for deterministic tests; {@code 0} = ThreadLocalRandom. */
		private long seed = 0L;
		/**
		 * Compute remote voter-subset fitness hints (SYNC_VOTERS). Does not rewrite live quorum.
		 */
		private boolean voterSetHintsEnabled = true;
		/** Target cardinality of recommended remote digest voters. */
		private int targetRemoteVoters = 1;
		/**
		 * When true, ReplicationCoordinator applies the latest voter-set recommendation
		 * via {@code OrchidNode.applyRemoteVoters} (remote digest voters only; local R unchanged).
		 * Default false — operator must opt in; never silent quorum rewrite.
		 */
		private boolean applyVoterSetHints = true;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public int getDcPopulationSize() {
			return dcPopulationSize;
		}

		public void setDcPopulationSize(int dcPopulationSize) {
			this.dcPopulationSize = dcPopulationSize;
		}

		public int getNodePopulationSize() {
			return nodePopulationSize;
		}

		public void setNodePopulationSize(int nodePopulationSize) {
			this.nodePopulationSize = nodePopulationSize;
		}

		public int getGenerationsPerTick() {
			return generationsPerTick;
		}

		public void setGenerationsPerTick(int generationsPerTick) {
			this.generationsPerTick = generationsPerTick;
		}

		public double getMutationRate() {
			return mutationRate;
		}

		public void setMutationRate(double mutationRate) {
			this.mutationRate = mutationRate;
		}

		public double getDiversityWeight() {
			return diversityWeight;
		}

		public void setDiversityWeight(double diversityWeight) {
			this.diversityWeight = diversityWeight;
		}

		public int getMaxMigratesPerTick() {
			return maxMigratesPerTick;
		}

		public void setMaxMigratesPerTick(int maxMigratesPerTick) {
			this.maxMigratesPerTick = maxMigratesPerTick;
		}

		public long getSeed() {
			return seed;
		}

		public void setSeed(long seed) {
			this.seed = seed;
		}

		public boolean isVoterSetHintsEnabled() {
			return voterSetHintsEnabled;
		}

		public void setVoterSetHintsEnabled(boolean voterSetHintsEnabled) {
			this.voterSetHintsEnabled = voterSetHintsEnabled;
		}

		public int getTargetRemoteVoters() {
			return targetRemoteVoters;
		}

		public void setTargetRemoteVoters(int targetRemoteVoters) {
			this.targetRemoteVoters = targetRemoteVoters;
		}

		public boolean isApplyVoterSetHints() {
			return applyVoterSetHints;
		}

		public void setApplyVoterSetHints(boolean applyVoterSetHints) {
			this.applyVoterSetHints = applyVoterSetHints;
		}
	}

	/**
	 * Replication TCP transport bind / peers.
	 */
	public static class TransportProps {
		private String bindHost = "0.0.0.0";
		private int bindPort = 5615;
		private long connectTimeoutMs = 5000;
		private int maxFrameBytes = 16 * 1024 * 1024;
		private List<ReplicationPeerProps> peers = new ArrayList<>();

		public String getBindHost() {
			return bindHost;
		}

		public void setBindHost(String bindHost) {
			this.bindHost = bindHost;
		}

		public int getBindPort() {
			return bindPort;
		}

		public void setBindPort(int bindPort) {
			this.bindPort = bindPort;
		}

		public long getConnectTimeoutMs() {
			return connectTimeoutMs;
		}

		public void setConnectTimeoutMs(long connectTimeoutMs) {
			this.connectTimeoutMs = connectTimeoutMs;
		}

		public int getMaxFrameBytes() {
			return maxFrameBytes;
		}

		public void setMaxFrameBytes(int maxFrameBytes) {
			this.maxFrameBytes = maxFrameBytes;
		}

		public List<ReplicationPeerProps> getPeers() {
			return peers;
		}

		public void setPeers(List<ReplicationPeerProps> peers) {
			this.peers = peers;
		}
	}

	/**
	 * Single replication peer endpoint.
	 */
	public static class ReplicationPeerProps {
		private String id;
		private String host;
		private int port;
		private String dc;

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getHost() {
			return host;
		}

		public void setHost(String host) {
			this.host = host;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public String getDc() {
			return dc;
		}

		public void setDc(String dc) {
			this.dc = dc;
		}
	}

	/**
	 * Cross-DC ship / sync-voter settings.
	 */
	public static class CrossDcProps {
		private boolean enabled = true;
		private String localDc = "dc-a";
		/**
		 * {@code ASYNC_SHIP} — async ship to remote {@link #learners} (or all remotes if empty).
		 * {@code SYNC_VOTERS_ACROSS_DC} — remote digest/commit voters (WAN ACK before ORCHID commit);
		 * learners stay async-only.
		 */
		private String mode = "ASYNC_SHIP";
		private int batchMaxOps = 512;
		private long batchMaxWaitMs = 20;
		/**
		 * When true, MutationRecorder also blocks on remote APPLY_ACK after OpLog (apply-level).
		 * Independent of {@code SYNC_VOTERS_ACROSS_DC} digest voting at ORCHID commit.
		 */
		private boolean requireRemoteAck;
		/** WAN timeout for remote voter digests / optional APPLY_ACK wait (ms). */
		private long remoteAckTimeoutMs = 5_000L;
		/**
		 * Opt-in: include remote voters in local Kuramoto R / phase coupling.
		 * Default false — multi-DC voting is digest-only on the WAN path.
		 */
		private boolean phaseCoupling;
		/**
		 * When false, this node is a Cross-DC learner/replica: never {@code writerEligible};
		 * SQL reads fail-closed when apply lag is stale or primary-DC link is down.
		 * Default true (primary / write-capable DC).
		 */
		private boolean writeAdmission = true;
		/**
		 * Remote-DC node ids that are sync commit voters when mode is {@code SYNC_VOTERS_ACROSS_DC}.
		 * Empty → all remote peers except {@link #learners}.
		 */
		private List<String> voters = new ArrayList<>();
		/**
		 * Remote-DC async catch-up targets. For {@code ASYNC_SHIP}, non-empty list is the ship
		 * target set (single-writer primary ships only to these learners).
		 */
		private List<String> learners = new ArrayList<>();

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getLocalDc() {
			return localDc;
		}

		public void setLocalDc(String localDc) {
			this.localDc = localDc;
		}

		public String getMode() {
			return mode;
		}

		public void setMode(String mode) {
			this.mode = mode;
		}

		public int getBatchMaxOps() {
			return batchMaxOps;
		}

		public void setBatchMaxOps(int batchMaxOps) {
			this.batchMaxOps = batchMaxOps;
		}

		public long getBatchMaxWaitMs() {
			return batchMaxWaitMs;
		}

		public void setBatchMaxWaitMs(long batchMaxWaitMs) {
			this.batchMaxWaitMs = batchMaxWaitMs;
		}

		public boolean isRequireRemoteAck() {
			return requireRemoteAck;
		}

		public void setRequireRemoteAck(boolean requireRemoteAck) {
			this.requireRemoteAck = requireRemoteAck;
		}

		public long getRemoteAckTimeoutMs() {
			return remoteAckTimeoutMs;
		}

		public void setRemoteAckTimeoutMs(long remoteAckTimeoutMs) {
			this.remoteAckTimeoutMs = remoteAckTimeoutMs;
		}

		public boolean isPhaseCoupling() {
			return phaseCoupling;
		}

		public void setPhaseCoupling(boolean phaseCoupling) {
			this.phaseCoupling = phaseCoupling;
		}

		public boolean isWriteAdmission() {
			return writeAdmission;
		}

		public void setWriteAdmission(boolean writeAdmission) {
			this.writeAdmission = writeAdmission;
		}

		public List<String> getVoters() {
			return voters;
		}

		public void setVoters(List<String> voters) {
			this.voters = voters;
		}

		public List<String> getLearners() {
			return learners;
		}

		public void setLearners(List<String> learners) {
			this.learners = learners;
		}
	}

	/**
	 * OpLog segment / dataDir / fsync.
	 */
	public static class OpLogProps {
		private int segmentSize = 1024;
		/** Root directory for OpLog + orchid state (always on disk). */
		private String dataDir = "./data/replication";
		/** fsync after each append (safer, slower). */
		private boolean fsync = true;

		public int getSegmentSize() {
			return segmentSize;
		}

		public void setSegmentSize(int segmentSize) {
			this.segmentSize = segmentSize;
		}

		public String getDataDir() {
			return dataDir;
		}

		public void setDataDir(String dataDir) {
			this.dataDir = dataDir;
		}

		public boolean isFsync() {
			return fsync;
		}

		public void setFsync(boolean fsync) {
			this.fsync = fsync;
		}
	}

	/**
	 * Replication flow control.
	 */
	public static class FlowProps {
		private int maxInflightOps = 10000;
		private List<String> domains = new ArrayList<>();

		public int getMaxInflightOps() {
			return maxInflightOps;
		}

		public void setMaxInflightOps(int maxInflightOps) {
			this.maxInflightOps = maxInflightOps;
		}

		public List<String> getDomains() {
			return domains;
		}

		public void setDomains(List<String> domains) {
			this.domains = domains;
		}
	}

	/**
	 * Codec root (duplex).
	 */
	public static class CodecProps {
		private DuplexProps duplex = new DuplexProps();

		public DuplexProps getDuplex() {
			return duplex;
		}

		public void setDuplex(DuplexProps duplex) {
			this.duplex = duplex;
		}
	}

	/**
	 * Soft overlay store (TTL/pin/QoS).
	 */
	public static class OverlayProps {
		/** Soft read-time annotations (TTL/pin/QoS); not a second row store. */
		private boolean enabled = false;
		/** Persist overlays under `{data-dir}/{cluster}/{node}/overlay/`. */
		private boolean durable = false;
		/**
		 * When {@code > 0}, auto-pin hot writes with this TTL (ms). {@code 0} = off.
		 * Uses {@link org.genfork.grid.overlay.OverlayStore} only (no second placement engine).
		 */
		private long autoPinTtlMs = 0L;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isDurable() {
			return durable;
		}

		public void setDurable(boolean durable) {
			this.durable = durable;
		}

		public long getAutoPinTtlMs() {
			return autoPinTtlMs;
		}

		public void setAutoPinTtlMs(long autoPinTtlMs) {
			this.autoPinTtlMs = autoPinTtlMs;
		}
	}

	/**
	 * Quartet duplex codec settings.
	 */
	public static class DuplexProps {
		private boolean enabled = true;
		private boolean verifyOnWrite = true;
		private boolean verifyOnRead = true;
		private String repairMode = "REBUILD_DATA_FROM_PARITY";
		private long schemaEpoch = 1L;
		private DuplexApplyProps applyTo = new DuplexApplyProps();

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isVerifyOnWrite() {
			return verifyOnWrite;
		}

		public void setVerifyOnWrite(boolean verifyOnWrite) {
			this.verifyOnWrite = verifyOnWrite;
		}

		public boolean isVerifyOnRead() {
			return verifyOnRead;
		}

		public void setVerifyOnRead(boolean verifyOnRead) {
			this.verifyOnRead = verifyOnRead;
		}

		public String getRepairMode() {
			return repairMode;
		}

		public void setRepairMode(String repairMode) {
			this.repairMode = repairMode;
		}

		public long getSchemaEpoch() {
			return schemaEpoch;
		}

		public void setSchemaEpoch(long schemaEpoch) {
			this.schemaEpoch = schemaEpoch;
		}

		public DuplexApplyProps getApplyTo() {
			return applyTo;
		}

		public void setApplyTo(DuplexApplyProps applyTo) {
			this.applyTo = applyTo;
		}
	}

	/**
	 * Where duplex encoding applies.
	 */
	public static class DuplexApplyProps {
		private boolean mapValues = true;
		private boolean replicationLog = true;

		public boolean isMapValues() {
			return mapValues;
		}

		public void setMapValues(boolean mapValues) {
			this.mapValues = mapValues;
		}

		public boolean isReplicationLog() {
			return replicationLog;
		}

		public void setReplicationLog(boolean replicationLog) {
			this.replicationLog = replicationLog;
		}
	}

	/**
	 * SQL engine / client defaults.
	 */
	public static class SqlProps {
		private int defaultShards = 4;
		private String dataDir = "./data/catalog";
		/** Optional grid:// URL for remote client (not JDBC/R2DBC). */
		private String url;
		private Integer maxConnections;
		private Boolean warmup;
		private Integer maxTxContexts;
		/**
		 * Max prepared statements retained per {@code SqlSession} (LRU eviction).
		 * Default 64; {@code <= 0} means unbounded.
		 */
		private int preparePoolSize = 64;
		/**
		 * Max wait for SQL record lock acquire (ms). Default
		 * {@link org.genfork.grid.sql.tx.SqlRecordLockManager#DEFAULT_LOCK_WAIT_MS}.
		 * Must stay ≤ typical client OP_TIMEOUT.
		 */
		private long lockWaitTimeoutMs = SqlRecordLockManager.DEFAULT_LOCK_WAIT_MS;
		/**
		 * Max table schemas retained in {@link CatalogMetaCache} (LRU).
		 * Default {@link CatalogMetaCache#DEFAULT_SIZE}; {@code <= 0} = unbounded.
		 */
		private int catalogMetaCacheSize = CatalogMetaCache.DEFAULT_SIZE;
		/** Default schema when not in URL path. */
		private String schema;
		private Long execTimeoutMs;
		private Long connectTimeoutMs;
		private Long readTimeoutMs;
		private Long writeTimeoutMs;
		private Integer maxRetries;
		private Long retryDelayMs;
		private String retryMode;
		private RemoteSqlProps remote = new RemoteSqlProps();
		/**
		 * Optional SQL peer endpoints for distributed SELECT / JOIN key fan-out
		 * ({@code grid.sql.distributed-peers}). Empty = local-only.
		 */
		private List<RemoteSqlProps> distributedPeers = new ArrayList<>();
		/**
		 * Max {@code WITH RECURSIVE} iteration depth (strict default).
		 * Values {@code <= 0} are rejected at execute time.
		 */
		private int recursiveCteMaxDepth = 32;
		/**
		 * Default SQL session timezone (IANA id) for zone-less {@code TIMESTAMPTZ} literals / binds.
		 * Default {@code UTC}.
		 */
		private String timezone = "UTC";

		public int getDefaultShards() {
			return defaultShards;
		}

		public void setDefaultShards(int defaultShards) {
			this.defaultShards = defaultShards;
		}

		public String getDataDir() {
			return dataDir;
		}

		public void setDataDir(String dataDir) {
			this.dataDir = dataDir;
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public Integer getMaxConnections() {
			return maxConnections;
		}

		public void setMaxConnections(Integer maxConnections) {
			this.maxConnections = maxConnections;
		}

		public Boolean getWarmup() {
			return warmup;
		}

		public void setWarmup(Boolean warmup) {
			this.warmup = warmup;
		}

		public Integer getMaxTxContexts() {
			return maxTxContexts;
		}

		public void setMaxTxContexts(Integer maxTxContexts) {
			this.maxTxContexts = maxTxContexts;
		}

		public int getPreparePoolSize() {
			return preparePoolSize;
		}

		public void setPreparePoolSize(int preparePoolSize) {
			this.preparePoolSize = preparePoolSize;
		}

		public long getLockWaitTimeoutMs() {
			return lockWaitTimeoutMs;
		}

		public void setLockWaitTimeoutMs(long lockWaitTimeoutMs) {
			this.lockWaitTimeoutMs = lockWaitTimeoutMs;
		}

		public int getCatalogMetaCacheSize() {
			return catalogMetaCacheSize;
		}

		public void setCatalogMetaCacheSize(int catalogMetaCacheSize) {
			this.catalogMetaCacheSize = catalogMetaCacheSize;
		}

		public String getSchema() {
			return schema;
		}

		public void setSchema(String schema) {
			this.schema = schema;
		}

		public Long getExecTimeoutMs() {
			return execTimeoutMs;
		}

		public void setExecTimeoutMs(Long execTimeoutMs) {
			this.execTimeoutMs = execTimeoutMs;
		}

		public Long getConnectTimeoutMs() {
			return connectTimeoutMs;
		}

		public void setConnectTimeoutMs(Long connectTimeoutMs) {
			this.connectTimeoutMs = connectTimeoutMs;
		}

		public Long getReadTimeoutMs() {
			return readTimeoutMs;
		}

		public void setReadTimeoutMs(Long readTimeoutMs) {
			this.readTimeoutMs = readTimeoutMs;
		}

		public Long getWriteTimeoutMs() {
			return writeTimeoutMs;
		}

		public void setWriteTimeoutMs(Long writeTimeoutMs) {
			this.writeTimeoutMs = writeTimeoutMs;
		}

		public Integer getMaxRetries() {
			return maxRetries;
		}

		public void setMaxRetries(Integer maxRetries) {
			this.maxRetries = maxRetries;
		}

		public Long getRetryDelayMs() {
			return retryDelayMs;
		}

		public void setRetryDelayMs(Long retryDelayMs) {
			this.retryDelayMs = retryDelayMs;
		}

		public String getRetryMode() {
			return retryMode;
		}

		public void setRetryMode(String retryMode) {
			this.retryMode = retryMode;
		}

		public RemoteSqlProps getRemote() {
			return remote;
		}

		public void setRemote(RemoteSqlProps remote) {
			this.remote = remote;
		}

		public List<RemoteSqlProps> getDistributedPeers() {
			return distributedPeers;
		}

		public void setDistributedPeers(List<RemoteSqlProps> distributedPeers) {
			this.distributedPeers = distributedPeers;
		}

		public int getRecursiveCteMaxDepth() {
			return recursiveCteMaxDepth;
		}

		public void setRecursiveCteMaxDepth(int recursiveCteMaxDepth) {
			this.recursiveCteMaxDepth = recursiveCteMaxDepth;
		}

		public String getTimezone() {
			return timezone;
		}

		public void setTimezone(String timezone) {
			this.timezone = timezone;
		}
	}

	/**
	 * Remote SQL peer / client endpoint props.
	 */
	public static class RemoteSqlProps {
		private String host;
		private int port = 15432;
		private String user = "";
		private String password = "";
		private int maxConnections = 1;
		private int maxTxContexts = 256;
		private boolean warmup = false;
		private String schema = "public";
		private long execTimeoutMs = 0L;
		private long connectTimeoutMs = 5000L;
		private long readTimeoutMs = 0L;
		private long writeTimeoutMs = 0L;
		private int maxRetries = 0;
		private long retryDelayMs = 200L;
		private String retryMode = "OFF";

		public String getHost() {
			return host;
		}

		public void setHost(String host) {
			this.host = host;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public String getUser() {
			return user;
		}

		public void setUser(String user) {
			this.user = user;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public int getMaxConnections() {
			return maxConnections;
		}

		public void setMaxConnections(int maxConnections) {
			this.maxConnections = maxConnections;
		}

		public int getMaxTxContexts() {
			return maxTxContexts;
		}

		public void setMaxTxContexts(int maxTxContexts) {
			this.maxTxContexts = maxTxContexts;
		}

		public boolean isWarmup() {
			return warmup;
		}

		public void setWarmup(boolean warmup) {
			this.warmup = warmup;
		}

		public String getSchema() {
			return schema;
		}

		public void setSchema(String schema) {
			this.schema = schema;
		}

		public long getExecTimeoutMs() {
			return execTimeoutMs;
		}

		public void setExecTimeoutMs(long execTimeoutMs) {
			this.execTimeoutMs = execTimeoutMs;
		}

		public long getConnectTimeoutMs() {
			return connectTimeoutMs;
		}

		public void setConnectTimeoutMs(long connectTimeoutMs) {
			this.connectTimeoutMs = connectTimeoutMs;
		}

		public long getReadTimeoutMs() {
			return readTimeoutMs;
		}

		public void setReadTimeoutMs(long readTimeoutMs) {
			this.readTimeoutMs = readTimeoutMs;
		}

		public long getWriteTimeoutMs() {
			return writeTimeoutMs;
		}

		public void setWriteTimeoutMs(long writeTimeoutMs) {
			this.writeTimeoutMs = writeTimeoutMs;
		}

		public int getMaxRetries() {
			return maxRetries;
		}

		public void setMaxRetries(int maxRetries) {
			this.maxRetries = maxRetries;
		}

		public long getRetryDelayMs() {
			return retryDelayMs;
		}

		public void setRetryDelayMs(long retryDelayMs) {
			this.retryDelayMs = retryDelayMs;
		}

		public String getRetryMode() {
			return retryMode;
		}

		public void setRetryMode(String retryMode) {
			this.retryMode = retryMode;
		}
	}

	/**
	 * Embedded SQL TCP server bind props.
	 */
	public static class SqlServerProps {
		private boolean enabled = false;
		private String host = "0.0.0.0";
		private int port = 15432;
		private String user = "grid";
		private String password = "grid";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getHost() {
			return host;
		}

		public void setHost(String host) {
			this.host = host;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public String getUser() {
			return user;
		}

		public void setUser(String user) {
			this.user = user;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}
	}
}
