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
package org.genfork.grid.replication.util;

import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * HELLO-path sealed baseline ship when a peer is behind OpLog truncate watermark.
 * <p>
 * OpLog catch-up alone cannot fill seqs retired by {@link OpLog#truncateTo}; peers need
 * sealed {@code .gmap} artifacts then the live OpLog tail. Skip-unchanged via
 * {@link SealedPeerShipGate} (peer + pack identity) with in-flight TTL after push;
 * confirm via inventory match ({@link #markPeerInventoryMatched}).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class HelloSealedCatchUpUtil {

	private static final Logger log = LoggerFactory.getLogger(HelloSealedCatchUpUtil.class);

	private static final SealedPeerShipGate PEER_SHIP_GATE = new SealedPeerShipGate();

	private HelloSealedCatchUpUtil() {
	}

	/**
	 * {@code true} when peer ACK is strictly behind the durable truncate watermark for the stream.
	 */
	public static boolean needsSealedBaseline(OpLog opLog, String domainType, int shard, long peerAck) {
		if (opLog == null || domainType == null) {
			return false;
		}
		final long truncatedThrough = opLog.truncatedThrough(domainType, shard);
		return truncatedThrough > 0L && peerAck < truncatedThrough;
	}

	/**
	 * Pack and push sealed shard artifacts to {@code peerId}.
	 * Ships only when pack is complete (≥1 valid {@code .gmap}); rejects partial/header-only.
	 *
	 * @return {@code true} when a complete pack was pushed
	 */
	public static boolean shipSealedBaseline(
			SealedGridMapService sealed,
			NettyReplicationTransport transport,
			String peerId,
			String domainType,
			int shard
	) {
		if (sealed == null || transport == null || peerId == null || domainType == null) {
			return false;
		}
		try {
			final byte[] packed = sealed.packShardArtifacts(domainType, shard);
			if (packed == null || packed.length == 0 || !SealedShardPack.isCompleteShardArtifacts(packed)) {
				return false;
			}
			final long packFp = SealedShardPack.identityFingerprintOfPacked(packed);
			final long now = System.currentTimeMillis();
			if (!PEER_SHIP_GATE.shouldShip(peerId, domainType, shard, packFp, now)) {
				return false;
			}
			transport.pushSealedShardPack(peerId, domainType, shard, packed);
			// In-flight debounce only — confirm via inventory match / peer ACK clearing needsSealedBaseline.
			PEER_SHIP_GATE.notePushInFlight(peerId, domainType, shard, packFp, now);
			log.info("HELLO sealed catch-up shipped peer={} domain={} shard={} bytes={}",
					peerId, domainType, shard, packed.length);
			return true;
		} catch (IOException failure) {
			log.warn("HELLO sealed catch-up failed peer={} domain={} shard={}: {}",
					peerId, domainType, shard, failure.toString());
			return false;
		}
	}

	/**
	 * Peer HELLO inventory now matches local pack identity — stop reshipping this fingerprint.
	 */
	public static void markPeerInventoryMatched(String peerId, String domainType, int shard, long fingerprint) {
		if (peerId == null || domainType == null || fingerprint == 0L) {
			return;
		}
		PEER_SHIP_GATE.onShipped(peerId, domainType, shard, fingerprint);
	}

	/**
	 * Allow reship of the same fingerprint (peer still incomplete after prior push).
	 */
	public static void clearPeerShipGate(String peerId, String domainType, int shard) {
		PEER_SHIP_GATE.clear(peerId, domainType, shard);
	}

	/**
	 * {@code true} when in-flight TTL has not expired or inventory already matched for this peer/stream.
	 */
	public static boolean isPeerShipGateActive(String peerId, String domainType, int shard) {
		return PEER_SHIP_GATE.isActive(peerId, domainType, shard);
	}
}
