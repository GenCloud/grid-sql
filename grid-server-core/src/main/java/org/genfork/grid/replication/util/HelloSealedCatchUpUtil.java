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
 * sealed {@code .gmap} artifacts then the live OpLog tail.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class HelloSealedCatchUpUtil {

	private static final Logger log = LoggerFactory.getLogger(HelloSealedCatchUpUtil.class);

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
	 * Pack and push sealed shard artifacts to {@code peerId}. No-op when sealed root empty/missing.
	 *
	 * @return {@code true} when a non-empty pack was pushed
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
			if (packed == null || packed.length == 0 || !SealedShardPack.hasArtifactFiles(packed)) {
				return false;
			}
			transport.pushSealedShardPack(peerId, domainType, shard, packed);
			log.info("HELLO sealed catch-up shipped peer={} domain={} shard={} bytes={}",
					peerId, domainType, shard, packed.length);
			return true;
		} catch (IOException failure) {
			log.warn("HELLO sealed catch-up failed peer={} domain={} shard={}: {}",
					peerId, domainType, shard, failure.toString());
			return false;
		}
	}
}