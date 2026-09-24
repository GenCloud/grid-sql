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

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationProps;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.netty.ReplicationRpcCodec;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.RegionClaimAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.RegionClaimReq;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.region.RegionClaimInFlight;
import org.genfork.grid.replication.region.RegionClaimQuorum;
import org.genfork.grid.replication.region.RegionRole;
import org.genfork.grid.replication.region.RegionRoleCoordinator;
import org.genfork.grid.threading.ThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Region claim RPC, promotion listeners, writer eligibility, and region/HA gates.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class RegionClaimService {
	private static final Logger log = LoggerFactory.getLogger(RegionClaimService.class);

	private static final int CLAIM_SILENCE_CONFIRM_POLLS = 2;
	private static final long REGION_CLAIM_ACK_TIMEOUT_MS = 2_000L;

	private final boolean enabled;
	private final boolean peerTransportEnabled;
	private final boolean crossDcEnabled;
	private final boolean writeAdmission;
	private final boolean replicaReadsEnabled;
	private final long maxStaleLag;
	private final ReplicationNodeState nodeState;
	private final OpLog opLog;
	private final OrchidNode orchidNode;
	private final NettyReplicationTransport nettyTransport;
	private final RegionRoleCoordinator regionCoordinator;
	private final List<Runnable> promotionListeners = new CopyOnWriteArrayList<>();
	private volatile boolean observedWriterEligible;
	private volatile String observedPromoteHint;
	private volatile long observedRegionEpoch;
	private int claimSilenceConfirmStreak;
	private volatile RegionClaimInFlight regionClaimInFlight;
	private final AtomicBoolean regionTipCatchUpPending = new AtomicBoolean(false);

	public RegionClaimService(
			boolean enabled,
			boolean peerTransportEnabled,
			boolean crossDcEnabled,
			boolean writeAdmission,
			boolean replicaReadsEnabled,
			long maxStaleLag,
			ReplicationNodeState nodeState,
			OpLog opLog,
			OrchidNode orchidNode,
			NettyReplicationTransport nettyTransport,
			RegionRoleCoordinator regionCoordinator
	) {
		this.enabled = enabled;
		this.peerTransportEnabled = peerTransportEnabled;
		this.crossDcEnabled = crossDcEnabled;
		this.writeAdmission = writeAdmission;
		this.replicaReadsEnabled = replicaReadsEnabled;
		this.maxStaleLag = maxStaleLag;
		this.nodeState = nodeState;
		this.opLog = opLog;
		this.orchidNode = orchidNode;
		this.nettyTransport = nettyTransport;
		this.regionCoordinator = regionCoordinator;
	}

	public void seedObservedState() {
		observedWriterEligible = isWriterEligible();
		observedPromoteHint = promoteHint();
		observedRegionEpoch = regionEpoch();
	}

	public RegionRoleCoordinator regionCoordinator() {
		return regionCoordinator;
	}

	public boolean isWriterEligible() {
		if (!enabled || orchidNode == null || nodeState == null) {
			return false;
		}
		if (!isWriteAdmission()) {
			return false;
		}
		if (!regionAllowsWrites()) {
			return false;
		}
		if (regionTipCatchUpPending.get()) {
			refreshRegionTipCatchUp();
			if (regionTipCatchUpPending.get()) {
				return false;
			}
		}
		return nodeState.isSynced() && orchidNode.isPhaseRankedProposer();
	}

	private void refreshRegionTipCatchUp() {
		if (orchidNode == null || !regionTipCatchUpPending.get()) {
			return;
		}
		final long peerTip = orchidNode.maxSeenPeerCommittedSeq();
		if (peerTip <= orchidNode.getLastCommittedSeq()) {
			regionTipCatchUpPending.set(false);
		}
	}

	public boolean regionAllowsWrites() {
		if (regionCoordinator == null || !regionCoordinator.isEnabled()) {
			return true;
		}
		return regionCoordinator.regionAllowsWrites();
	}

	public long regionEpoch() {
		if (regionCoordinator == null || !regionCoordinator.isEnabled()) {
			return 0L;
		}
		return regionCoordinator.regionEpoch();
	}

	public byte regionRoleWire() {
		if (regionCoordinator == null || !regionCoordinator.isEnabled()) {
			return 0;
		}
		return regionCoordinator.regionRole().wireCode();
	}

	public boolean observePeerRegionEpoch(long peerEpoch, String peerNodeId) {
		if (regionCoordinator == null || !regionCoordinator.isEnabled()) {
			return false;
		}
		final boolean fenced = regionCoordinator.observePeerEpoch(peerEpoch, peerNodeId);
		if (fenced && peerTransportEnabled && nettyTransport != null) {
			nettyTransport.broadcastHello();
			ThreadService.getLogicExecutor().execute(this::firePromotionListeners);
		}
		return fenced;
	}

	public boolean noteRemotePeerRegion(long peerEpoch, byte peerRoleWire) {
		if (regionCoordinator == null || !regionCoordinator.isEnabled()) {
			return false;
		}
		final boolean fenced = regionCoordinator.noteRemotePeerRegion(peerEpoch, peerRoleWire);
		if (fenced && peerTransportEnabled && nettyTransport != null) {
			nettyTransport.broadcastHello();
			ThreadService.getLogicExecutor().execute(this::firePromotionListeners);
		}
		return fenced;
	}

	public boolean tryRegionClaim(int ackCount) {
		if (regionCoordinator == null || !regionCoordinator.isEnabled()) {
			return false;
		}
		return regionCoordinator.tryCompleteClaim(ackCount);
	}

	public void addPromotionListener(Runnable listener) {
		if (listener != null) {
			promotionListeners.add(listener);
		}
	}

	public void removePromotionListener(Runnable listener) {
		if (listener != null) {
			promotionListeners.remove(listener);
		}
	}

	public void pollPromotionState() {
		maybeRegionClaim();
		final boolean writerEligible = isWriterEligible();
		final String promoteHint = promoteHint();
		final long epoch = regionEpoch();
		if (writerEligible == observedWriterEligible
				&& Objects.equals(promoteHint, observedPromoteHint)
				&& epoch == observedRegionEpoch) {
			return;
		}
		observedWriterEligible = writerEligible;
		observedPromoteHint = promoteHint;
		observedRegionEpoch = epoch;
		ThreadService.getLogicExecutor().execute(this::firePromotionListeners);
	}

	private void maybeRegionClaim() {
		if (regionCoordinator == null || !regionCoordinator.isEnabled()) {
			return;
		}
		if (regionClaimInFlight != null) {
			expireRegionClaimIfTimedOut();
			return;
		}
		if (!regionCoordinator.shouldAttemptClaim(hasActiveRemoteDcLink())) {
			claimSilenceConfirmStreak = 0;
			return;
		}
		claimSilenceConfirmStreak++;
		if (claimSilenceConfirmStreak < CLAIM_SILENCE_CONFIRM_POLLS) {
			return;
		}
		claimSilenceConfirmStreak = 0;
		final long proposedEpoch = Math.max(regionCoordinator.regionEpoch(), regionCoordinator.observedClusterEpoch()) + 1L;
		final RegionClaimInFlight inflight = RegionClaimInFlight.start(
				nodeState.getNodeId(),
				proposedEpoch,
				regionCoordinator.claimQuorumVoters());
		regionClaimInFlight = inflight;
		if (peerTransportEnabled && nettyTransport != null && !nettyTransport.peers().isEmpty()) {
			nettyTransport.broadcastRegionClaimReq(
					inflight.claimId(),
					inflight.proposedEpoch(),
					regionCoordinator.regionRole().wireCode());
			log.info("Region claim REQ broadcast claimId={} proposedEpoch={} voters={}",
					inflight.claimId(), inflight.proposedEpoch(), inflight.voterCount());
		}
		tryFinishRegionClaim(inflight);
	}

	private void expireRegionClaimIfTimedOut() {
		final RegionClaimInFlight inflight = regionClaimInFlight;
		if (inflight == null) {
			return;
		}
		if (inflight.ageMs() < REGION_CLAIM_ACK_TIMEOUT_MS) {
			return;
		}
		log.warn("Region claim ACK timeout claimId={} acks={}/{}",
				inflight.claimId(), inflight.ackCount(), inflight.voterCount());
		regionClaimInFlight = null;
	}

	private void tryFinishRegionClaim(RegionClaimInFlight inflight) {
		if (inflight == null || !inflight.hasQuorum()) {
			return;
		}
		if (regionClaimInFlight != inflight) {
			return;
		}
		if (hasActiveRemoteDcLink()) {
			log.info("Region claim aborted — Active remote-DC link recovered claimId={}", inflight.claimId());
			regionClaimInFlight = null;
			return;
		}
		if (!tryRegionClaim(inflight.ackCount())) {
			regionClaimInFlight = null;
			return;
		}
		regionClaimInFlight = null;
		if (orchidNode != null) {
			final int sealed = orchidNode.sealBufferedCrossDcProposes();
			if (sealed > 0) {
				log.info("Region claim sealed {} buffered cross-DC propose(s) tip={}",
						sealed, orchidNode.getLastCommittedSeq());
			}
			final long peerTip = orchidNode.maxSeenPeerCommittedSeq();
			if (peerTip > orchidNode.getLastCommittedSeq()) {
				regionTipCatchUpPending.set(true);
				log.info("Region claim tip catch-up pending localTip={} peerTip={}",
						orchidNode.getLastCommittedSeq(), peerTip);
			} else {
				regionTipCatchUpPending.set(false);
			}
		}
		if (peerTransportEnabled && nettyTransport != null) {
			nettyTransport.broadcastHello();
		}
		observedWriterEligible = isWriterEligible();
		observedPromoteHint = promoteHint();
		observedRegionEpoch = regionEpoch();
		ThreadService.getLogicExecutor().execute(this::firePromotionListeners);
		log.info("Region claim succeeded epoch={} roleWire={} acks={}",
				regionEpoch(), regionRoleWire(), inflight.ackCount());
	}

	public byte[] onRegionClaimReqBuildAck(RegionClaimReq req) {
		if (regionCoordinator == null || !regionCoordinator.isEnabled() || req == null) {
			return null;
		}
		final RegionRole localRole = regionCoordinator.regionRole();
		if (localRole != RegionRole.HOLD && localRole != RegionRole.WITNESS) {
			return null;
		}
		if (req.fromNodeId() != null && req.fromNodeId().equals(nodeState.getNodeId())) {
			return null;
		}
		return ReplicationRpcCodec.encodeRegionClaimAck(
				req.claimId(), nodeState.getNodeId(), localRole.wireCode());
	}

	public void onRegionClaimAck(RegionClaimAck ack) {
		final RegionClaimInFlight inflight = regionClaimInFlight;
		if (inflight == null || ack == null || ack.claimId() != inflight.claimId()) {
			return;
		}
		final RegionRole voterRole = RegionRole.fromWire(ack.voterRoleWire());
		if (voterRole != RegionRole.HOLD && voterRole != RegionRole.WITNESS) {
			return;
		}
		if (inflight.recordAck(ack.fromNodeId())) {
			log.debug("Region claim ACK from={} claimId={} acks={}",
					ack.fromNodeId(), ack.claimId(), inflight.ackCount());
		}
		tryFinishRegionClaim(inflight);
	}

	private void firePromotionListeners() {
		for (Runnable listener : promotionListeners) {
			try {
				listener.run();
			} catch (RuntimeException ex) {
				log.warn("Promotion-state listener failed: {}", ex.toString());
			}
		}
	}

	public boolean isWriteAdmission() {
		if (!enabled) {
			return true;
		}
		if (writeAdmission) {
			return true;
		}
		return regionCoordinator != null
				&& regionCoordinator.isEnabled()
				&& regionCoordinator.regionRole() == RegionRole.ACTIVE;
	}

	public boolean hasActiveRemoteDcLink() {
		if (!enabled || !crossDcEnabled || nettyTransport == null || nodeState == null) {
			return true;
		}
		final String localDc = nodeState.getLocalDc();
		return nettyTransport.hasActivePeerOutsideDc(localDc);
	}

	public String promoteHint() {
		if (!enabled || orchidNode == null) {
			return null;
		}
		return orchidNode.phaseRankedProposerId();
	}

	public long maxStaleLag() {
		return maxStaleLag;
	}

	public boolean isApplyLagStale() {
		if (!enabled || nodeState == null || opLog == null) {
			return false;
		}
		if (maxStaleLag == Long.MAX_VALUE) {
			return false;
		}
		return nodeState.maxApplyLag(opLog) > maxStaleLag;
	}

	public boolean isReplicaReadsEnabled() {
		return replicaReadsEnabled;
	}

	public boolean regionAllowsReplicaReads() {
		if (regionCoordinator == null || !regionCoordinator.isEnabled()) {
			return true;
		}
		final RegionRole role = regionCoordinator.regionRole();
		return role == RegionRole.ACTIVE || role == RegionRole.HOLD;
	}

	public void noteActivePeerLink() {
		if (regionCoordinator != null && regionCoordinator.isEnabled()) {
			regionCoordinator.noteActivePeerLink();
		}
	}

	public static RegionRoleCoordinator createRegionCoordinator(
			ReplicationProps cfg,
			Path dataDir,
			boolean fsync
	) {
		final GridConfigurationProperties.RegionProps region =
				cfg.getRegion() == null
						? new GridConfigurationProperties.RegionProps()
						: cfg.getRegion();
		try {
			return new RegionRoleCoordinator(
					dataDir,
					fsync,
					region.isEnabled(),
					region.getRole() == null ? RegionRole.ACTIVE : region.getRole(),
					region.getEpoch(),
					cfg.getNodeId(),
					cfg.getCrossDc() == null ? "" : cfg.getCrossDc().getLocalDc(),
					region.getClaimTimeoutMs(),
					region.getQuorumSize());
		} catch (Exception ex) {
			throw new IllegalStateException("region epoch coordinator init failed", ex);
		}
	}
}
