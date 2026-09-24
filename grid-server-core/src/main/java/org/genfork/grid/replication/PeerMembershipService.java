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

import java.util.ArrayList;
import java.util.List;

import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationPeerProps;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.join.SparseCatchUp;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.region.RegionRole;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.transport.DiscoveredPeer;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.replication.transport.ReplicationPublisher;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Peer membership, isolate/reconnect, HELLO catch-up, and segment ship helpers.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class PeerMembershipService {
	private static final Logger log = LoggerFactory.getLogger(PeerMembershipService.class);

	private final boolean enabled;
	private final boolean crossDcEnabled;
	private final boolean repairEnabled;
	private final ReplicationNodeState nodeState;
	private final OpLog opLog;
	private final OrchidNode orchidNode;
	private final NettyReplicationTransport nettyTransport;
	private final SparseCatchUp sparseCatchUp;
	private final HomologousRepair homologousRepair;
	private final ReplicationPublisher publisher;
	private final CrossDcPublisher crossDcPublisher;
	private final List<ReplicationPeer> peers;
	private final RegionClaimService regionClaimService;

	public PeerMembershipService(
			boolean enabled,
			boolean crossDcEnabled,
			boolean repairEnabled,
			ReplicationNodeState nodeState,
			OpLog opLog,
			OrchidNode orchidNode,
			NettyReplicationTransport nettyTransport,
			SparseCatchUp sparseCatchUp,
			HomologousRepair homologousRepair,
			ReplicationPublisher publisher,
			CrossDcPublisher crossDcPublisher,
			List<ReplicationPeer> peers,
			RegionClaimService regionClaimService
	) {
		this.enabled = enabled;
		this.crossDcEnabled = crossDcEnabled;
		this.repairEnabled = repairEnabled;
		this.nodeState = nodeState;
		this.opLog = opLog;
		this.orchidNode = orchidNode;
		this.nettyTransport = nettyTransport;
		this.sparseCatchUp = sparseCatchUp;
		this.homologousRepair = homologousRepair;
		this.publisher = publisher;
		this.crossDcPublisher = crossDcPublisher;
		this.peers = peers;
		this.regionClaimService = regionClaimService;
	}

	public synchronized void addPeer(ReplicationPeer peer) {
		if (!enabled || peer == null) {
			return;
		}
		peers.removeIf(p -> peer.id().equals(p.id()));
		peers.add(peer);
		nettyTransport.addPeer(peer);
		final String localDc = nodeState.getLocalDc();
		if (peer.dc() == null || localDc == null || localDc.equals(peer.dc())) {
			orchidNode.configureLocalPeer(peer.id());
		} else {
			orchidNode.onPeerAvailable(peer.id());
		}
		publisher.setPeers(peers);
		crossDcPublisher.setRemotePeers(peers);
	}

	public synchronized void removePeer(String peerId) {
		if (!enabled || peerId == null) {
			return;
		}
		peers.removeIf(p -> peerId.equals(p.id()));
		nettyTransport.removePeer(peerId);
		orchidNode.unconfigurePeer(peerId);
		sparseCatchUp.forget(peerId);
		publisher.setPeers(peers);
		crossDcPublisher.setRemotePeers(peers);
	}

	public synchronized void isolatePeer(String peerId) {
		if (!enabled || peerId == null) {
			return;
		}
		nettyTransport.removePeer(peerId);
		orchidNode.isolatePeer(peerId);
		sparseCatchUp.forget(peerId);
	}

	public synchronized void reconnectPeer(String peerId) {
		if (!enabled || peerId == null) {
			return;
		}
		orchidNode.healPeer(peerId);
		for (ReplicationPeer peer : peers) {
			if (peerId.equals(peer.id())) {
				nettyTransport.addPeer(peer);
				orchidNode.onPeerAvailable(peer.id());
				return;
			}
		}
	}

	public void onPeerDiscovered(DiscoveredPeer discovered) {
		if (discovered == null || discovered.id() == null) {
			return;
		}
		final boolean known = peers.stream().anyMatch(p -> discovered.id().equals(p.id()));
		if (!known && discovered.port() > 0) {
			addPeer(new ReplicationPeer(discovered.id(), discovered.host(), discovered.port(), discovered.dc()));
		}
		if (regionClaimService != null
				&& regionClaimService.regionCoordinator() != null
				&& regionClaimService.regionCoordinator().isEnabled()) {
			final String localDc = nodeState.getLocalDc() == null ? "" : nodeState.getLocalDc();
			final String peerDc = discovered.dc() == null ? "" : discovered.dc();
			final boolean remoteDc = !localDc.isEmpty()
					&& !peerDc.isEmpty()
					&& !localDc.equalsIgnoreCase(peerDc);
			if (remoteDc) {
				regionClaimService.noteRemotePeerRegion(discovered.regionEpoch(), discovered.regionRole());
			} else {
				regionClaimService.observePeerRegionEpoch(discovered.regionEpoch(), discovered.id());
			}
			if (discovered.regionRole() == RegionRole.ACTIVE.wireCode()
					&& remoteDc) {
				regionClaimService.noteActivePeerLink();
			}
		}
		onPeerHello(discovered.id());
	}

	private void onPeerHello(String peerId) {
		sparseCatchUp.onLearnerJoin(peerId);
		for (String streamKey : opLog.streamKeys()) {
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final String domain = parsed.domain();
			final int shard = parsed.shard();
			final long peerWm = nodeState.peerAck(peerId, domain, shard);
			sparseCatchUp.catchUpAndShip(domain, shard, peerWm, (pid, ops) -> {
				if (ops.isEmpty()) {
					return;
				}
				final OpLogSegment segment = new OpLogSegment(
						domain, shard, ops.getFirst().opSeq(), ops.getLast().opSeq(), ops,
						OpLogCodec.segmentChecksum(ops)
				);
				nettyTransport.pushSegment(pid, segment);
			}, peerId);
		}
		if (publisher != null) {
			publisher.flushAll();
		}
		if (repairEnabled && homologousRepair != null) {
			for (String streamKey : opLog.streamKeys()) {
				final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
				if (parsed == null) {
					continue;
				}
				final String domain = parsed.domain();
				final int shard = parsed.shard();
				final long lastSeq = opLog.lastSeq(domain, shard);
				final long peerAck = nodeState.peerAck(peerId, domain, shard);
				// HELLO catch-up only when peer is behind — avoid full locus ship on every reconnect.
				if (lastSeq > 0L && peerAck >= lastSeq) {
					continue;
				}
				nettyTransport.sendRepairRequest(peerId, domain, shard,
						homologousRepair.getLocusMap().snapshot(domain, shard));
			}
		}
	}

	public void maybePromoteVoter(String peerId) {
		if (peerId == null || sparseCatchUp.isReadyForVoterPromote(peerId)) {
			return;
		}
		if (opLog.streamKeys().isEmpty()) {
			sparseCatchUp.markReadyForVoterPromote(peerId);
			return;
		}
		for (String streamKey : opLog.streamKeys()) {
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final String domain = parsed.domain();
			final int shard = parsed.shard();
			final long last = opLog.lastSeq(domain, shard);
			if (last > 0 && nodeState.peerAck(peerId, domain, shard) < last) {
				return;
			}
		}
		sparseCatchUp.markReadyForVoterPromote(peerId);
	}

	public void shipSameDc(ReplicationPeer peer, OpLogSegment segment) {
		deliverSegment(peer, segment, false);
	}

	public void shipCrossDc(ReplicationPeer peer, OpLogSegment segment) {
		deliverSegment(peer, segment, true);
	}

	private void deliverSegment(ReplicationPeer peer, OpLogSegment segment, boolean crossDc) {
		try {
			nettyTransport.pushSegment(peer.id(), segment);
		} catch (RuntimeException ex) {
			if (crossDc && crossDcPublisher != null) {
				crossDcPublisher.getMetrics().recordShipFailure();
			}
			log.warn("Netty OPLOG_PUSH to {} failed: {}", peer.id(), ex.toString());
		}
	}

	public static List<ReplicationPeer> toPeers(List<ReplicationPeerProps> props) {
		if (props == null) {
			return List.of();
		}
		final List<ReplicationPeer> peers = new ArrayList<>();
		for (ReplicationPeerProps p : props) {
			peers.add(new ReplicationPeer(p.getId(), p.getHost(), p.getPort(), p.getDc()));
		}
		return peers;
	}
}
