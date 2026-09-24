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
package org.genfork.grid.replication.snapshot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.repair.HomologousRepair;

/**
 * OpLog-range hydrate and bootstrap marker segments (sealed GMAP is SoT for map rows).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public class SnapshotService {
	/** OpLog read batch size during hydrate replay. */
	private static final int HYDRATE_BATCH = 10_000;

	private final OpLog opLog;
	private final ReplicationNodeState nodeState;

	public SnapshotService(OpLog opLog, ReplicationNodeState nodeState) {
		this.opLog = opLog;
		this.nodeState = nodeState;
	}

	public OpLogSegment createMarkerSegment(String domainType, int shard) {
		final long seq = Math.max(1L, opLog.lastSeq(domainType, shard));
		final ReplicationOp marker = OpLogCodec.withChecksum(new ReplicationOp(
				domainType,
				shard,
				seq,
				ReplicationOpType.SNAPSHOT_MARKER,
				new byte[]{0},
				null,
				nodeState.getSchemaEpoch(),
				0L
		));
		return new OpLogSegment(domainType, shard, seq, seq, List.of(marker), OpLogCodec.segmentChecksum(List.of(marker)));
	}

	public int installFromOpLog(String domainType, int shard, long fromSeqInclusive, int limit, ReplicaApplier applier) {
		final List<ReplicationOp> ops = opLog.readFrom(domainType, shard, fromSeqInclusive, limit);
		if (ops.isEmpty() || applier == null) {
			return 0;
		}
		int applied = 0;
		for (ReplicationOp op : ops) {
			applier.apply(op, true);
			applied++;
		}
		return applied;
	}

	/**
	 * Replay OpLog from watermark+1 for all shards of the domain (sealed SoT already loaded by caller).
	 * Incomplete TX units left open after replay are discarded (no partial map rows).
	 */
	public int hydrateDomain(String domainType, ReplicaApplier applier, HomologousRepair repair) {
		if (domainType == null || applier == null) {
			return 0;
		}
		int total = 0;
		for (String streamKey : opLog.streamKeys()) {
			if (!streamKey.startsWith(domainType + "#")) {
				continue;
			}
			final int hash = streamKey.lastIndexOf('#');
			final int shard = Integer.parseInt(streamKey.substring(hash + 1));
			final long wm = nodeState.appliedWatermark(domainType, shard);
			total += hydrateShardFromWatermark(domainType, shard, wm, applier, repair);
		}
		applier.discardOpenTxStaging();
		return total;
	}

	/**
	 * LAZY mode: do not load rows; return known shard ids for on-demand {@link #hydrateShard}.
	 */
	public Set<Integer> listShards(String domainType) {
		final LinkedHashSet<Integer> shards = new LinkedHashSet<>();
		if (domainType == null) {
			return shards;
		}
		for (String streamKey : opLog.streamKeys()) {
			if (!streamKey.startsWith(domainType + "#")) {
				continue;
			}
			final int hash = streamKey.lastIndexOf('#');
			shards.add(Integer.parseInt(streamKey.substring(hash + 1)));
		}
		return shards;
	}

	/**
	 * Load one shard into the bound processors via OpLog tail only.
	 */
	public int hydrateShard(
			String domainType,
			int shard,
			ReplicaApplier applier,
			HomologousRepair repair
	) {
		if (domainType == null || applier == null) {
			return 0;
		}
		final long wm = nodeState.appliedWatermark(domainType, shard);
		final int n = hydrateShardFromWatermark(domainType, shard, wm, applier, repair);
		applier.discardOpenTxStaging();
		return n;
	}

	/**
	 * Replay OpLog from watermark+1 only.
	 */
	public int hydrateOpLogFrom(
			String domainType,
			int shard,
			long snapWm,
			ReplicaApplier applier,
			HomologousRepair repair
	) {
		return hydrateShardFromWatermark(domainType, shard, snapWm, applier, repair);
	}

	private int hydrateShardFromWatermark(
			String domainType,
			int shard,
			long snapWm,
			ReplicaApplier applier,
			HomologousRepair repair
	) {
		int total = 0;
		if (snapWm <= 0) {
			nodeState.resetApplied(domainType, shard);
		}
		long from = Math.max(1L, snapWm + 1);
		applier.beginHydrateMapOnly();
		try {
			while (true) {
				final List<ReplicationOp> batch = opLog.readFrom(domainType, shard, from, HYDRATE_BATCH);
				if (batch.isEmpty()) {
					break;
				}
				for (ReplicationOp op : batch) {
					if (repair != null) {
						repair.observe(op);
					}
					applier.apply(op, true);
					total++;
					from = op.opSeq() + 1;
				}
				// One mailbox batch per OpLog read chunk — not N×indexNow / not full map reindex.
				applier.flushHydrateIndexDelta(shard);
				if (batch.size() < HYDRATE_BATCH) {
					break;
				}
			}
		} finally {
			applier.endHydrateMapOnly();
		}
		return total;
	}
}
