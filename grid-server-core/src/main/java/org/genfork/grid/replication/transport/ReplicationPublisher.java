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
package org.genfork.grid.replication.transport;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.flow.ReplicationFlowControl;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.tx.OpLogTxUnits;
import org.genfork.grid.replication.tx.TxUnitShardBuffer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

/**
 * Publishes appended ops to same-DC peers (and feeds pull catch-up).
 * Ships only complete TX units (parity with {@code CrossDcPublisher}).
 * <p>
 * Permit wait uses {@link LockSupport#parkNanos} (VT-friendly). Flush runs on a dedicated
 * ship thread — never hold buffer monitors across Netty write.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicationPublisher {
	private static final long ACQUIRE_PARK_NS = 1_000_000L;
	private static final int ACQUIRE_MAX_WAIT_MS = 5_000;

	private final ReplicationNodeState nodeState;
	private final OpLog opLog;
	private final ReplicationFlowControl flowControl;
	private final int segmentSize;
	private final List<ReplicationPeer> peers = new CopyOnWriteArrayList<>();
	private final Map<String, TxUnitShardBuffer> buffers = new ConcurrentHashMap<>();
	private BiConsumer<ReplicationPeer, OpLogSegment> shipper;
	private final ExecutorService shipExecutor = Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "repl-same-dc-ship");
		t.setDaemon(true);
		return t;
	});

	public ReplicationPublisher(ReplicationNodeState nodeState,
	                            OpLog opLog,
	                            ReplicationFlowControl flowControl,
	                            int segmentSize) {
		this.nodeState = nodeState;
		this.opLog = opLog;
		this.flowControl = flowControl;
		this.segmentSize = Math.max(1, segmentSize);
	}

	public List<ReplicationPeer> getPeers() {
		return peers;
	}

	public void setPeers(List<ReplicationPeer> peers) {
		this.peers.clear();
		this.peers.addAll(peers);
	}

	public void setShipper(BiConsumer<ReplicationPeer, OpLogSegment> shipper) {
		this.shipper = shipper;
	}

	public void onAppended(ReplicationOp op) {
		if (!flowControl.allowsDomain(op.domainType())) {
			return;
		}
		acquirePermit();
		final String key = op.domainType() + "#" + op.shard();
		final TxUnitShardBuffer buffer = buffers.computeIfAbsent(key, k -> new TxUnitShardBuffer());
		buffer.offer(op);
		final ReplicationOpType type = op.type();
		final boolean unitBoundary = type == ReplicationOpType.TX_COMMIT
				|| type == ReplicationOpType.TX_ABORT;
		if (unitBoundary || buffer.size() >= segmentSize) {
			shipExecutor.execute(() -> flushBuffer(buffer));
		}
	}

	/** Test helper: ops held waiting for TX unit completion. */
	@VisibleForTesting
	public int bufferedOpCount(String domainType, int shard) {
		final TxUnitShardBuffer buffer = buffers.get(domainType + "#" + shard);
		return buffer == null ? 0 : buffer.size();
	}

	/** Park until flow permit; never silently drop a committed op. VT-safe (no Thread.sleep). */
	private void acquirePermit() {
		final long deadline = System.currentTimeMillis() + ACQUIRE_MAX_WAIT_MS;
		while (!flowControl.tryAcquire()) {
			ReplicationMetrics.recordShipBackpressure();
			if (System.currentTimeMillis() >= deadline) {
				LockSupport.parkNanos(ACQUIRE_PARK_NS);
				if (Thread.interrupted()) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Interrupted waiting for ship permit");
				}
				if (flowControl.tryAcquire()) {
					return;
				}
				deadlineExtendPark();
				continue;
			}
			LockSupport.parkNanos(ACQUIRE_PARK_NS);
			if (Thread.interrupted()) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted waiting for ship permit");
			}
		}
	}

	private void deadlineExtendPark() {
		while (!flowControl.tryAcquire()) {
			ReplicationMetrics.recordShipBackpressure();
			LockSupport.parkNanos(ACQUIRE_PARK_NS);
			if (Thread.interrupted()) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted waiting for ship permit");
			}
		}
	}

	public void flushAll() {
		for (TxUnitShardBuffer buffer : buffers.values()) {
			flushBuffer(buffer);
		}
	}

	public OpLogSegment pull(String domainType, int shard, long fromSeq, int limit) {
		final OpLogSegment raw = opLog.segmentFrom(domainType, shard, fromSeq, limit);
		final List<ReplicationOp> complete = OpLogTxUnits.trimToCompleteUnits(raw.ops());
		if (complete.isEmpty()) {
			return new OpLogSegment(domainType, shard, fromSeq, fromSeq - 1, List.of(), 0L);
		}
		return new OpLogSegment(
				domainType,
				shard,
				complete.getFirst().opSeq(),
				complete.getLast().opSeq(),
				complete,
				OpLogCodec.segmentChecksum(complete)
		);
	}

	private void flushBuffer(TxUnitShardBuffer buffer) {
		final List<ReplicationOp> batch = buffer.drainCompleteUnits();
		if (batch.isEmpty()) {
			return;
		}
		if (shipper == null) {
			flowControl.release(batch.size());
			return;
		}
		final ReplicationOp first = batch.getFirst();
		final OpLogSegment segment = new OpLogSegment(
				first.domainType(),
				first.shard(),
				first.opSeq(),
				batch.getLast().opSeq(),
				batch,
				OpLogCodec.segmentChecksum(batch)
		);
		for (ReplicationPeer peer : peers) {
			if (peer.dc() != null && peer.dc().equals(nodeState.getLocalDc())) {
				shipper.accept(peer, segment);
			}
		}
		flowControl.release(batch.size());
	}
}
