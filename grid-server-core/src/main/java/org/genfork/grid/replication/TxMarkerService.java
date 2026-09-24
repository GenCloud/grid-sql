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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.log.StreamOpLogAppender;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.transport.ReplicationPublisher;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;

/**
 * Durable TX marker path: OpLog holdback admit->join->publish, force, and side-effects.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class TxMarkerService {
	/** Fallback domain when a stream ref omits domain. */
	private static final String DEFAULT_TX_DOMAIN = "_tx";

	private final boolean enabled;
	private final boolean crossDcEnabled;
	private final ReplicationNodeState nodeState;
	private final OpLog opLog;
	private final StreamOpLogAppender streamOpLogAppender;
	private final OrchidNode orchidNode;
	private final ReplicationPublisher publisher;
	private final CrossDcPublisher crossDcPublisher;
	private final HomologousRepair homologousRepair;

	public TxMarkerService(
			boolean enabled,
			boolean crossDcEnabled,
			ReplicationNodeState nodeState,
			OpLog opLog,
			StreamOpLogAppender streamOpLogAppender,
			OrchidNode orchidNode,
			ReplicationPublisher publisher,
			CrossDcPublisher crossDcPublisher,
			HomologousRepair homologousRepair
	) {
		this.enabled = enabled;
		this.crossDcEnabled = crossDcEnabled;
		this.nodeState = nodeState;
		this.opLog = opLog;
		this.streamOpLogAppender = streamOpLogAppender;
		this.orchidNode = orchidNode;
		this.publisher = publisher;
		this.crossDcPublisher = crossDcPublisher;
		this.homologousRepair = homologousRepair;
	}

	public void recordTxMarker(ReplicationOpType type, long txId, String domainType, int shard) {
		recordTxMarker(type, txId, domainType, shard, null);
	}

	/**
	 * Durable TX marker. For multi-stream envelopes, {@code TX_BEGIN} value carries membership
	 * ({@code TxEnvelopeCodec}); other markers keep {@code value == null}.
	 */
	public void recordTxMarker(ReplicationOpType type, long txId, String domainType, int shard,
	                           byte[] value) {
		if (!enabled || orchidNode == null || type == null) {
			return;
		}
		recordTxMarkersBatch(type, txId,
				List.of(new TxEnvelopeCodec.StreamRef(domainType, shard)),
				value);
	}

	/**
	 * TX markers across streams via {@link StreamOpLogAppender} holdback; one force per stream;
	 * one orchid confirm tip.
	 * <p>
	 * Per-stream admit->join->publish (not all-admit-then-join): holding {@code beginJoin} on stream
	 * A while blocked admitting stream B under {@code maxProposeInFlight} stalls every concurrent
	 * writer whose ready seq sits above A's inflight slot (WRITE_BATCH multi-shard cliff).
	 */
	public void recordTxMarkersBatch(ReplicationOpType type, long txId,
	                                 List<TxEnvelopeCodec.StreamRef> streams,
	                                 byte[] value) {
		if (!enabled || orchidNode == null || type == null || streams == null || streams.isEmpty()) {
			return;
		}
		if (streamOpLogAppender == null) {
			throw new IllegalStateException("OpLog holdback not initialized");
		}
		final byte[] key = Long.toHexString(txId).getBytes(StandardCharsets.UTF_8);
		final List<ReplicationOp> committedOps = new ArrayList<>(streams.size());
		for (TxEnvelopeCodec.StreamRef stream : streams) {
			final String domain = stream.domain() == null ? DEFAULT_TX_DOMAIN : stream.domain();
			final int shard = stream.shard();
			final ReplicationOp raw = OpLogCodec.withChecksum(new ReplicationOp(
					domain, shard, Math.max(1L, opLog.lastSeq(domain, shard) + 1),
					type, key, value, nodeState.getSchemaEpoch(), 0L
			));
			final OrchidNode.AdmittedPropose admitted = orchidNode.appendAndAdmit(raw, expected -> {
				if (expected > 0L) {
					streamOpLogAppender.beginJoin(domain, shard, expected);
				}
			});
			boolean joined = false;
			try {
				final long seq = admitted.future().join();
				joined = true;
				final ReplicationOp committed = OpLogCodec.withChecksum(new ReplicationOp(
						domain, shard, seq, type, key, value, raw.schemaEpoch(), 0L));
				streamOpLogAppender.publishCommitted(committed);
				committedOps.add(committed);
			} catch (CompletionException ex) {
				if (!joined) {
					streamOpLogAppender.cancelJoin(domain, shard, admitted.expectedOpSeq());
				}
				if (!committedOps.isEmpty()) {
					forceMarkerStreams(committedOps);
					finishMarkerSideEffects(committedOps);
				}
				final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
				if (cause instanceof RuntimeException runtime) {
					throw runtime;
				}
				throw new IllegalStateException("TX marker orchid commit failed", cause);
			}
		}
		forceMarkerStreams(committedOps);
		finishMarkerSideEffects(committedOps);
	}

	private void finishMarkerSideEffects(List<ReplicationOp> committedOps) {
		for (ReplicationOp committed : committedOps) {
			nodeState.advanceApplied(committed.domainType(), committed.shard(), committed.opSeq());
			if (homologousRepair != null) {
				homologousRepair.observe(committed);
			}
			if (publisher != null) {
				publisher.onAppended(committed);
			}
			if (crossDcEnabled && crossDcPublisher != null) {
				crossDcPublisher.onAppended(committed);
			}
		}
	}

	private void forceMarkerStreams(List<ReplicationOp> committedOps) {
		final Set<String> forcedStreams = new HashSet<>();
		long tipSeq = 0L;
		for (ReplicationOp committed : committedOps) {
			if (committed.opSeq() > tipSeq) {
				tipSeq = committed.opSeq();
			}
			final String streamKey = OpLogStreamKeyUtil.format(committed.domainType(), committed.shard());
			if (forcedStreams.add(streamKey)) {
				streamOpLogAppender.force(committed.domainType(), committed.shard());
			}
		}
		if (tipSeq > 0L) {
			orchidNode.confirmPersisted(tipSeq);
		}
	}
}