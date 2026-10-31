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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Close orphan / sticky multi-stream apply envelopes before gated sealed dump.
 * <p>
 * LAZY hydrate of stream A can {@code TX_COMMIT} A (clear local {@code openTxId}) while
 * sibling streams never run — {@link TxEnvelopeCoordinator#hasOpenApplyMembershipForStream}
 * stays true forever and sealed dump starves ({@code ENVELOPE} defer). Fully-committed
 * envelopes left in {@code applyOpen} (missed take) are sticky leftovers — cleared here.
 * Mid-TX ENVELOPE defer remains correct.
 * <p>
 * <b>FORBIDDEN:</b> skip ENVELOPE dump gate; raise OpLog-without-{@code .sbpt} ceiling.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class EnvelopeClosureHydrateUtil {
	private static final Logger log = LoggerFactory.getLogger(EnvelopeClosureHydrateUtil.class);

	private EnvelopeClosureHydrateUtil() {
	}

	/**
	 * Hydrate one sibling stream's OpLog tail (no huge-delta reject — caller is closure only).
	 */
	@FunctionalInterface
	public interface SiblingOpLogHydrate {
		/**
		 * Replay OpLog for {@code domain#shard} through its applier.
		 */
		void hydrate(String domain, int shard, ReplicaApplier applier);
	}

	/**
	 * Before any gated sealed dump of {@code domain}: close orphan/sticky apply membership
	 * on every listed shard so ENVELOPE defer cannot starve seal forever.
	 */
	public static void prepareSealForDomain(
			TxEnvelopeCoordinator gate,
			Map<String, ReplicaApplier> appliers,
			String domain,
			Iterable<Integer> shards,
			SiblingOpLogHydrate siblingHydrate
	) {
		if (gate == null || appliers == null || domain == null || domain.isBlank()
				|| shards == null || siblingHydrate == null) {
			return;
		}
		for (Integer shardObj : shards) {
			if (shardObj == null) {
				continue;
			}
			final int shard = shardObj.intValue();
			if (!gate.hasOpenApplyMembershipForStream(domain, shard)
					&& gate.applyMembershipTxIdsListingStream(domain, shard).isEmpty()) {
				continue;
			}
			completeOrAbortAfterStreamHydrate(gate, appliers, domain, shard, siblingHydrate);
		}
	}

	/**
	 * After hydrating {@code domain#shard}, complete incomplete apply envelopes that list it
	 * by hydrating sibling streams; fail-closed abort leftovers; clear sticky fully-committed.
	 *
	 * @param siblingHydrate must not go through {@code rejectHugeOpLogDeltaWithoutSbpt}
	 */
	public static void completeOrAbortAfterStreamHydrate(
			TxEnvelopeCoordinator gate,
			Map<String, ReplicaApplier> appliers,
			String domain,
			int shard,
			SiblingOpLogHydrate siblingHydrate
	) {
		if (gate == null || appliers == null || domain == null || domain.isBlank() || siblingHydrate == null) {
			return;
		}
		final List<Long> incomplete = gate.incompleteApplyTxIdsListingStream(domain, shard);
		for (Long txIdObj : incomplete) {
			if (txIdObj == null) {
				continue;
			}
			final long txId = txIdObj.longValue();
			if (!gate.isMultiApplyOpen(txId)) {
				continue;
			}
			final Set<TxEnvelopeCodec.StreamRef> expected = gate.applyExpectedStreams(txId);
			if (expected.isEmpty()) {
				continue;
			}
			for (TxEnvelopeCodec.StreamRef sibling : expected) {
				if (sibling == null) {
					continue;
				}
				if (domain.equals(sibling.domain()) && sibling.shard() == shard) {
					continue;
				}
				if (gate.isApplyStreamCommitted(txId, sibling)) {
					continue;
				}
				final ReplicaApplier siblingApplier = appliers.get(sibling.domain());
				if (siblingApplier == null) {
					continue;
				}
				try {
					siblingHydrate.hydrate(sibling.domain(), sibling.shard(), siblingApplier);
				} catch (RuntimeException ex) {
					log.warn(
							"Envelope closure sibling hydrate failed txId={} self={}#{} sibling={}#{}: {}",
							txId, domain, shard, sibling.domain(), sibling.shard(), ex.toString());
				}
			}
			if (!gate.isMultiApplyOpen(txId)) {
				continue;
			}
			if (gate.isApplyFullyCommitted(txId)) {
				clearStickyEnvelope(gate, txId, domain, shard);
				continue;
			}
			if (anyLiveOpenTxOnExpected(appliers, expected)) {
				// Live mid-TX — keep ENVELOPE defer; never abort an in-flight unit.
				continue;
			}
			// Leftover orphan (no live openTx on membership streams): fail-closed abort.
			gate.discardApply(txId);
			ReplicationMetrics.recordOrphanApplyEnvelopeAbort();
			log.warn(
					"Orphan multi-stream apply envelope aborted after LAZY hydrate txId={} stream={}#{} (seal starvation guard)",
					txId, domain, shard);
		}
		clearStickyFullyCommittedListing(gate, domain, shard);
		if (gate.hasOpenApplyMembershipForStream(domain, shard)) {
			log.debug(
					"Apply membership still open after closure pass domain={} shard={} (live mid-TX OK)",
					domain, shard);
		}
	}

	/**
	 * {@code true} when any membership stream still has a live applier open TX
	 * ({@link ReplicaApplier#hasOpenTx}) — mid-TX must not be fail-closed aborted.
	 */
	private static boolean anyLiveOpenTxOnExpected(
			Map<String, ReplicaApplier> appliers,
			Set<TxEnvelopeCodec.StreamRef> expected
	) {
		if (appliers == null || expected == null || expected.isEmpty()) {
			return false;
		}
		for (TxEnvelopeCodec.StreamRef ref : expected) {
			if (ref == null) {
				continue;
			}
			final ReplicaApplier applier = appliers.get(ref.domain());
			if (applier != null && applier.hasOpenTx(ref.domain(), ref.shard())) {
				return true;
			}
		}
		return false;
	}

	private static void clearStickyFullyCommittedListing(
			TxEnvelopeCoordinator gate,
			String domain,
			int shard
	) {
		for (Long txIdObj : gate.applyMembershipTxIdsListingStream(domain, shard)) {
			if (txIdObj == null) {
				continue;
			}
			final long txId = txIdObj.longValue();
			if (!gate.isMultiApplyOpen(txId) || !gate.isApplyFullyCommitted(txId)) {
				continue;
			}
			clearStickyEnvelope(gate, txId, domain, shard);
		}
	}

	private static void clearStickyEnvelope(
			TxEnvelopeCoordinator gate,
			long txId,
			String domain,
			int shard
	) {
		Objects.requireNonNull(gate, "gate");
		gate.discardApply(txId);
		ReplicationMetrics.recordStickyApplyEnvelopeClear();
		log.warn(
				"Sticky fully-committed apply envelope cleared before seal dump txId={} stream={}#{}",
				txId, domain, shard);
	}

	/**
	 * Test/helper: walk incomplete txs (no hydrate).
	 */
	public static void forEachIncompleteListing(
			TxEnvelopeCoordinator gate,
			String domain,
			int shard,
			BiConsumer<Long, Set<TxEnvelopeCodec.StreamRef>> consumer
	) {
		if (gate == null || consumer == null) {
			return;
		}
		for (Long txId : gate.incompleteApplyTxIdsListingStream(domain, shard)) {
			consumer.accept(txId, gate.applyExpectedStreams(txId));
		}
	}
}
