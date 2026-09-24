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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.genfork.grid.replication.orchid.OrchidNode;

/**
 * Per-node replication runtime state (schema epoch, seq allocators, watermarks).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicationNodeState {
	private final String nodeId;
	private final String clusterId;
	private final String localDc;
	private volatile long schemaEpoch;
	private volatile OrchidNode orchidNode;

	private final Map<String, AtomicLong> nextOpSeq = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> appliedWatermark = new ConcurrentHashMap<>();
	private final Map<String, AtomicLong> peerAckWatermark = new ConcurrentHashMap<>();

	public ReplicationNodeState(String nodeId, String clusterId, String localDc, long schemaEpoch) {
		this.nodeId = nodeId;
		this.clusterId = clusterId;
		this.localDc = localDc;
		this.schemaEpoch = schemaEpoch;
	}

	public String getNodeId() {
		return nodeId;
	}

	public String getClusterId() {
		return clusterId;
	}

	public String getLocalDc() {
		return localDc;
	}

	public long getSchemaEpoch() {
		return schemaEpoch;
	}

	public OrchidNode getOrchidNode() {
		return orchidNode;
	}

	public void bindOrchid(OrchidNode orchidNode) {
		this.orchidNode = orchidNode;
	}

	public void setSchemaEpoch(long schemaEpoch) {
		this.schemaEpoch = schemaEpoch;
	}

	private static String key(String domainType, int shard) {
		return domainType + "#" + shard;
	}

	public long nextOpSeq(String domainType, int shard) {
		return nextOpSeq.computeIfAbsent(key(domainType, shard), k -> new AtomicLong(0)).incrementAndGet();
	}

	public void observeOpSeq(String domainType, int shard, long opSeq) {
		nextOpSeq.computeIfAbsent(key(domainType, shard), k -> new AtomicLong(0))
				.updateAndGet(cur -> Math.max(cur, opSeq));
	}

	public long appliedWatermark(String domainType, int shard) {
		return appliedWatermark.getOrDefault(key(domainType, shard), new AtomicLong(0)).get();
	}

	public void advanceApplied(String domainType, int shard, long opSeq) {
		appliedWatermark.computeIfAbsent(key(domainType, shard), k -> new AtomicLong(0))
				.updateAndGet(cur -> Math.max(cur, opSeq));
		observeOpSeq(domainType, shard, opSeq);
	}

	/**
	 * After OpLog load: align nextOpSeq with durable lastSeq.
	 * Does not set applied watermark — {@link org.genfork.grid.replication.snapshot.SnapshotService#hydrateDomain}
	 * applies ops into the map and advances applied.
	 */
	public void seedFromOpLogLastSeq(String domainType, int shard, long lastSeq) {
		if (lastSeq <= 0) {
			return;
		}
		nextOpSeq.computeIfAbsent(key(domainType, shard), k -> new AtomicLong(0))
				.updateAndGet(cur -> Math.max(cur, lastSeq));
	}

	/** Reset applied watermark so OpLog hydrate can re-apply into an empty map. */
	public void resetApplied(String domainType, int shard) {
		appliedWatermark.put(key(domainType, shard), new AtomicLong(0));
	}

	/** Max (lastSeq - applied) across known streams; used by swarm applyLag sensor. */
	public long maxApplyLag(org.genfork.grid.replication.log.OpLog opLog) {
		if (opLog == null) {
			return 0L;
		}
		long max = 0L;
		for (String streamKey : opLog.streamKeys()) {
			final int hash = streamKey.lastIndexOf('#');
			if (hash <= 0) {
				continue;
			}
			final String domain = streamKey.substring(0, hash);
			final int shard = Integer.parseInt(streamKey.substring(hash + 1));
			final long lag = Math.max(0L, opLog.lastSeq(domain, shard) - appliedWatermark(domain, shard));
			max = Math.max(max, lag);
		}
		return max;
	}

	public void seedAllFromOpLog(org.genfork.grid.replication.log.OpLog opLog) {
		if (opLog == null) {
			return;
		}
		for (String streamKey : opLog.streamKeys()) {
			final int hash = streamKey.lastIndexOf('#');
			if (hash <= 0) {
				continue;
			}
			final String domain = streamKey.substring(0, hash);
			final int shard = Integer.parseInt(streamKey.substring(hash + 1));
			seedFromOpLogLastSeq(domain, shard, opLog.lastSeq(domain, shard));
		}
	}

	public void advancePeerAck(String peerId, String domainType, int shard, long opSeq) {
		peerAckWatermark.computeIfAbsent(peerId + "|" + key(domainType, shard), k -> new AtomicLong(0))
				.updateAndGet(cur -> Math.max(cur, opSeq));
	}

	/** Per-peer applied ACK watermark (0 if never acked). */
	public long peerAck(String peerId, String domainType, int shard) {
		if (peerId == null) {
			return 0L;
		}
		final AtomicLong v = peerAckWatermark.get(peerId + "|" + key(domainType, shard));
		return v == null ? 0L : v.get();
	}

	public long minPeerAck(String domainType, int shard) {
		final String suffix = "|" + key(domainType, shard);
		long min = Long.MAX_VALUE;
		boolean any = false;
		for (Map.Entry<String, AtomicLong> e : peerAckWatermark.entrySet()) {
			if (e.getKey().endsWith(suffix)) {
				any = true;
				min = Math.min(min, e.getValue().get());
			}
		}
		return any ? min : 0L;
	}

	public boolean isSynced() {
		return orchidNode != null && orchidNode.isSynced();
	}

	public double orchidR() {
		return orchidNode == null ? 0.0 : orchidNode.orderParameterR();
	}
}
