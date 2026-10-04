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

import org.genfork.grid.codec.duplex.DuplexBlob;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.mem.stage.GridEntriesProcessor.AddEntry;
import org.genfork.grid.mem.stage.GridEntriesProcessor.Entry;
import org.genfork.grid.mem.stage.GridEntriesProcessor.RemoveEntry;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.log.StreamOpLogAppender;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.orchid.OrchidNode;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.transport.ReplicationPublisher;
import org.genfork.grid.replication.tx.StreamCommitSerializer;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Records map mutations into ORCHID consensus + OpLog (when phase-synced).
 * Failures complete the future exceptionally (fail-closed for the commit listener).
 * <p>
 * Batch / TX units admit all proposes then join→publish so {@link StreamOpLogAppender}
 * holdback clears as each future completes; one {@link OpLog#force} + one orchid
 * {@code confirmPersisted} amortize fsync on the commit thread.
 * TX units with markers hold {@link StreamCommitSerializer} only around the admit
 * burst (contiguous tip), not during digest join — avoids same-stream lock-through-RTT.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class MutationRecorder {
	private static final int TX_MARKER_OVERHEAD_OPS = 2;

	private final ReplicationNodeState nodeState;
    private final StreamOpLogAppender streamAppender;
	private final String domainType;
	private final ReplicationPublisher publisher;
	private final CrossDcPublisher crossDcPublisher;
	private final HomologousRepair homologousRepair;
	private final StreamCommitSerializer streamCommitSerializer;

	public MutationRecorder(ReplicationNodeState nodeState,
                            String domainType,
	                        ReplicationPublisher publisher,
	                        CrossDcPublisher crossDcPublisher,
	                        HomologousRepair homologousRepair,
	                        StreamOpLogAppender streamAppender,
	                        StreamCommitSerializer streamCommitSerializer) {
		this.nodeState = nodeState;
        this.streamAppender = Objects.requireNonNull(streamAppender, "streamAppender");
		this.domainType = domainType;
		this.publisher = publisher;
		this.crossDcPublisher = crossDcPublisher;
		this.homologousRepair = homologousRepair;
		this.streamCommitSerializer = streamCommitSerializer;
	}

	public CompletableFuture<Long> recordCommitted(int shard, Entry entry) {
		final ReplicationOp template = toRawOp(shard, entry);
		final OrchidNode.AdmittedPropose admitted = orchidAdmitRaw(shard, template);
		return admitted.future().handle((committedSeq, err) -> {
			if (err != null) {
				streamAppender.cancelJoin(domainType, shard, admitted.expectedOpSeq());
				if (err instanceof RuntimeException re) {
					throw re;
				}
				throw new CompletionException(err);
			}
			final ReplicationOp committed = withCommittedSeq(template, committedSeq);
			streamAppender.publishCommitted(committed);
			forceDurableThenFinish(shard, List.of(committed));
			return committed.opSeq();
		});
	}

	/** Join orchid/OpLog path; unwraps CompletionException for callers. */
	public long recordCommittedBlocking(int shard, Entry entry) {
		try {
			return recordCommitted(shard, entry).join();
		} catch (CompletionException ex) {
			throw unwrap(ex);
		}
	}

	/**
	 * Pipelined orchid proposes for a same-stream batch; each join publishes immediately
	 * so holdback clears before later joins; one group force + tip confirm.
	 * Multi-shard TX callers hold {@link StreamCommitSerializer} around the full unit
	 * so concurrent units cannot interleave {@code TX_BEGIN} on one stream.
	 */
	public void recordCommittedBatchBlocking(int shard, List<Entry> entries) {
		if (entries == null || entries.isEmpty()) {
			return;
		}
		try {
			final List<ReplicationOp> templates = new ArrayList<>(entries.size());
			for (Entry entry : entries) {
				templates.add(toRawOp(shard, entry));
			}
			admitJoinForceAll(shard, templates);
		} catch (CompletionException ex) {
			throw unwrap(ex);
		}
	}

	/**
	 * TX unit: admit BEGIN+data+COMMIT under a short per-stream lock (contiguous tip),
	 * then join→publish and one force outside the lock.
	 * Single data op with no envelope skips markers (autocommit UPSERT path).
	 */
	public void recordTxUnitBlocking(int shard, long txId, byte[] beginValue, List<Entry> entries) {
		if (entries == null) {
			entries = List.of();
		}
		if (entries.size() == 1 && (beginValue == null || beginValue.length == 0)) {
			recordCommittedBatchBlocking(shard, entries);
			return;
		}
		try {
			final byte[] markerKey = Long.toHexString(txId).getBytes(StandardCharsets.UTF_8);
			final List<ReplicationOp> templates =
					new ArrayList<>(entries.size() + TX_MARKER_OVERHEAD_OPS);
			templates.add(markerTemplate(shard, ReplicationOpType.TX_BEGIN, markerKey, beginValue));
            for (Entry entry : entries) {
                templates.add(toRawOp(shard, entry));
            }
			templates.add(markerTemplate(shard, ReplicationOpType.TX_COMMIT, markerKey, null));
			final String streamKey = OpLogStreamKeyUtil.format(domainType, shard);
			final List<OrchidNode.AdmittedPropose> admitted;
			if (streamCommitSerializer != null) {
				// Templates built outside lock; only tip admits are serialized.
				admitted = streamCommitSerializer.callWithLock(streamKey,
						() -> admitAllRaw(shard, templates));
			} else {
				admitted = admitAllRaw(shard, templates);
			}
			joinForceAll(shard, templates, admitted);
		} catch (CompletionException ex) {
			throw unwrap(ex);
		}
	}

	/**
	 * Admit all templates then join→publish→force (no stream lock).
	 */
	private void admitJoinForceAll(int shard, List<ReplicationOp> templates) {
		joinForceAll(shard, templates, admitAllRaw(shard, templates));
	}

	/**
	 * Admit every template (tip fence / proposeFlight). Caller holds stream lock when
	 * contiguous TX markers are required.
	 */
	private List<OrchidNode.AdmittedPropose> admitAllRaw(int shard, List<ReplicationOp> templates) {
		final List<OrchidNode.AdmittedPropose> admitted = new ArrayList<>(templates.size());
        for (ReplicationOp template : templates) {
            admitted.add(orchidAdmitRaw(shard, template));
        }
		return admitted;
	}

	/**
	 * Join pipelined admits; publish each op as soon as its future completes so holdback
	 * does not retain {@code beginJoin} across later joins in the same batch; then force.
	 */
	private void joinForceAll(
			int shard,
			List<ReplicationOp> templates,
			List<OrchidNode.AdmittedPropose> admitted
	) {
		final List<ReplicationOp> committedOps = new ArrayList<>(admitted.size());
		joinPublishImmediate(admitted, templates, committedOps, shard);
		forceDurableThenFinish(shard, committedOps);
	}

	/**
	 * Join pipelined admits; publish each op as soon as its future completes so holdback
	 * does not retain {@code beginJoin} across later joins in the same batch.
	 */
	private void joinPublishImmediate(
			List<OrchidNode.AdmittedPropose> admitted,
			List<ReplicationOp> templates,
			List<ReplicationOp> committedOps,
			int shard
	) {
		int joined = 0;
		try {
			for (int i = 0; i < admitted.size(); i++) {
				final long seq = admitted.get(i).future().join();
				final ReplicationOp committed = withCommittedSeq(templates.get(i), seq);
				streamAppender.publishCommitted(committed);
				committedOps.add(committed);
				joined = i + 1;
			}
		} catch (CompletionException ex) {
			for (int i = joined; i < admitted.size(); i++) {
				streamAppender.cancelJoin(domainType, shard, admitted.get(i).expectedOpSeq());
			}
			if (!committedOps.isEmpty()) {
				forceDurableThenFinish(shard, committedOps);
			}
			throw ex;
		}
	}

	private ReplicationOp markerTemplate(
			int shard,
			ReplicationOpType type,
			byte[] key,
			byte[] value
	) {
		final long opSeqHint = nodeState.nextOpSeq(domainType, shard);
		return OpLogCodec.withChecksum(new ReplicationOp(
				domainType, shard, opSeqHint, type, key, value, nodeState.getSchemaEpoch(), 0L
		));
	}

	private OrchidNode.AdmittedPropose orchidAdmitRaw(int shard, ReplicationOp op) {
		final OrchidNode orchid = nodeState.getOrchidNode();
		if (orchid == null) {
			return new OrchidNode.AdmittedPropose(
					CompletableFuture.failedFuture(new IllegalStateException("ORCHID not bound")),
					0L);
		}
		final long orchidStartNs = System.nanoTime();
		final OrchidNode.AdmittedPropose admitted = orchid.appendAndAdmit(op, expected -> {
			if (expected > 0L) {
				streamAppender.beginJoin(domainType, shard, expected);
			}
		});
		final CompletableFuture<Long> timed = admitted.future().whenComplete((seq, err) ->
				ReplicationMetrics.recordOrchidWaitNs(System.nanoTime() - orchidStartNs));
		return new OrchidNode.AdmittedPropose(timed, admitted.expectedOpSeq());
	}

	private ReplicationOp toRawOp(int shard, Entry entry) {
		final ReplicationOpType type = entry instanceof RemoveEntry
				? ReplicationOpType.DELETE
				: ReplicationOpType.UPSERT;
		byte[] value = entry instanceof AddEntry ? entry.getValue() : null;
		if (DuplexCodecSupport.isActiveForReplication() && DuplexBlob.isWire(value)) {
			final DuplexBlob verified = DuplexCodecSupport.getCodec().getVerifier()
					.verifyOrRepair(DuplexBlob.fromWireBytes(value));
			value = verified.toWireBytes();
		}
		final long opSeqHint = nodeState.nextOpSeq(domainType, shard);
		return OpLogCodec.withChecksum(new ReplicationOp(
				domainType,
				shard,
				opSeqHint,
				type,
				entry.getKey(),
				value,
				nodeState.getSchemaEpoch(),
				0L
		));
	}

	private static ReplicationOp withCommittedSeq(ReplicationOp template, long committedSeq) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				template.domainType(), template.shard(), committedSeq, template.type(),
				template.key(), template.value(), template.schemaEpoch(), 0L));
	}

	/**
	 * OpLog group force then orchid tip confirm on the commit thread; then observe / ship.
	 * Tip force stays off the bounded I/O pool (AbortPolicy under CAPACITY 128t saturated IO).
	 */
	private void forceDurableThenFinish(int shard, List<ReplicationOp> committedOps) {
		if (committedOps == null || committedOps.isEmpty()) {
			return;
		}
		streamAppender.force(domainType, shard);
		long tipSeq = committedOps.getFirst().opSeq();
		for (ReplicationOp committed : committedOps) {
			if (committed.opSeq() > tipSeq) {
				tipSeq = committed.opSeq();
			}
		}
		final OrchidNode orchid = nodeState.getOrchidNode();
		if (orchid != null) {
			orchid.confirmPersisted(tipSeq);
		}
		publishAfterDurable(committedOps);
	}

	/** Observe / ship after OpLog + orchid tip are durable. */
	private void publishAfterDurable(List<ReplicationOp> committedOps) {
		for (ReplicationOp committed : committedOps) {
			nodeState.advanceApplied(domainType, committed.shard(), committed.opSeq());
			if (homologousRepair != null) {
				homologousRepair.observe(committed);
			}
			if (publisher != null) {
				publisher.onAppended(committed);
			}
			if (crossDcPublisher != null) {
				crossDcPublisher.onAppended(committed);
				if (crossDcPublisher.isRequireRemoteAck()) {
					crossDcPublisher.awaitRemoteAck(
							domainType, committed.shard(), committed.opSeq(),
							crossDcPublisher.getRemoteAckTimeoutMs());
				}
			}
		}
	}

	private static RuntimeException unwrap(CompletionException ex) {
		final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
		if (cause instanceof RuntimeException re) {
			return re;
		}
		return new IllegalStateException("Replication record failed", cause);
	}
}
