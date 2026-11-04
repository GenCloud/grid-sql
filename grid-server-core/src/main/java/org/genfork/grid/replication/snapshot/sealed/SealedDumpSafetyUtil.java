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
package org.genfork.grid.replication.snapshot.sealed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fail-closed gates for sealed dump so mid-TX / mid-envelope merge cannot
 * stamp dirty staging into sealed and truncate OpLog past an open unit.
 * <p>
 * Also rate-limits WARN for at-risk shards (huge OpLog delta without {@code .sbpt}).
 * <p>
 * <b>Invariants:</b> mid-TX {@link DeferReason#ENVELOPE} defer is safety — never skip.
 * Orphan membership after LAZY one-stream hydrate is closed by
 * {@link org.genfork.grid.replication.util.EnvelopeClosureHydrateUtil}, not by weakening
 * this gate or raising OpLog-without-{@code .sbpt} ceilings.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class SealedDumpSafetyUtil {
    private static final Logger log = LoggerFactory.getLogger(SealedDumpSafetyUtil.class);

    /** Min interval between INFO lines for deferred sealed dumps. */
    private static final long MIN_INFO_INTERVAL_NS = 5_000_000_000L;
    /** Min interval between WARN lines for at-risk (no {@code .sbpt}, huge delta) shards. */
    private static final long MIN_AT_RISK_WARN_INTERVAL_NS = 30_000_000_000L;
    private static final AtomicLong LAST_INFO_NS = new AtomicLong();
    private static final AtomicLong LAST_AT_RISK_WARN_NS = new AtomicLong();

	private SealedDumpSafetyUtil() {
	}

	/**
	 * {@code true} when OpLog tip is far ahead of sealed watermark and no index seal exists.
	 */
	public static boolean isAtRiskWithoutSbpt(long lastSeq, long sealedWm, boolean hasAnySbpt, long maxDelta) {
		if (hasAnySbpt || maxDelta <= 0L) {
			return false;
		}
		final long delta = Math.max(0L, lastSeq - Math.max(0L, sealedWm));
		return delta > maxDelta;
	}

	/**
	 * Rate-limited WARN + metric for a shard that must seal before LAZY reopen.
	 */
	public static void warnAtRiskWithoutSbpt(String domainType, int shard, long delta, long maxDelta) {
		ReplicationMetrics.recordSealedDumpAtRisk();
		final long now = System.nanoTime();
		final long prev = LAST_AT_RISK_WARN_NS.get();
		if (now - prev < MIN_AT_RISK_WARN_INTERVAL_NS) {
			return;
		}
		if (!LAST_AT_RISK_WARN_NS.compareAndSet(prev, now)) {
			return;
		}
		log.warn(
				"Sealed dump at-risk domain={} shard={} opLogDelta={} maxWithoutSbpt={} — dump when OPEN_TX/ENVELOPE clear",
				domainType, shard, delta, maxDelta);
	}

	/**
	 * Why a sealed dump shard was deferred (or {@link DeferReason#NONE}).
	 */
	public enum DeferReason {
		NONE,
		OPEN_TX,
		ENVELOPE
	}

	/**
	 * Defer reason for metrics / logging ({@link DeferReason#NONE} when dump may proceed).
	 */
	public static DeferReason deferReason(ReplicaApplier applier, String domainType, int shard) {
		if (applier == null || domainType == null) {
			return DeferReason.NONE;
		}

		if (applier.hasOpenTx(domainType, shard)) {
			return DeferReason.OPEN_TX;
		}

		final TxEnvelopeCoordinator envelope = applier.envelopeCoordinator();
		if (envelope != null && envelope.hasOpenApplyMembershipForStream(domainType, shard)) {
			return DeferReason.ENVELOPE;
		}

		return DeferReason.NONE;
	}

	/**
	 * Gate adapter for {@link SealedGridMapService#dumpDomain} (no deferred tracking).
	 */
	public static SealedGridMapService.DumpDeferGate deferGate(ReplicaApplier applier) {
		return trackingDeferGate(applier);
	}

	/**
	 * Tracking gate: records deferred shards/reasons for {@link DumpDomainResult}.
	 */
	public static TrackingDeferGate trackingDeferGate(ReplicaApplier applier) {
		return new TrackingDeferGate(applier);
	}

	/**
	 * {@link SealedGridMapService.DumpDeferGate} that accumulates defer outcomes.
	 */
	public static final class TrackingDeferGate implements SealedGridMapService.DumpDeferGate {
		private final ReplicaApplier applier;
		private final List<Integer> deferredShards = new ArrayList<>();
		private final List<DeferReason> deferredReasons = new ArrayList<>();

		TrackingDeferGate(ReplicaApplier applier) {
			this.applier = applier;
		}

		@Override
		public boolean shouldDefer(String domainType, int shard) {
			final DeferReason reason = deferReason(applier, domainType, shard);
			if (reason == DeferReason.NONE) {
				return false;
			}
			deferredShards.add(Integer.valueOf(shard));
			deferredReasons.add(reason);
			onShardDeferred(domainType, shard, reason);
			return true;
		}

		public List<Integer> deferredShards() {
			return List.copyOf(deferredShards);
		}

		public List<DeferReason> deferredReasons() {
			return List.copyOf(deferredReasons);
		}
	}

    /**
     * Record defer metrics and optionally emit a rate-limited INFO line.
     */
    static void onShardDeferred(
            String domainType,
            int shard,
            SealedDumpSafetyUtil.DeferReason reason
    ) {
        ReplicationMetrics.recordSealedDumpDeferred();
		if (reason == SealedDumpSafetyUtil.DeferReason.ENVELOPE) {
			ReplicationMetrics.recordEnvelopeDefer();
		}

        if (reason == null || reason == SealedDumpSafetyUtil.DeferReason.NONE) {
            return;
        }

        final long now = System.nanoTime();
        final long prev = LAST_INFO_NS.get();
        if (now - prev < MIN_INFO_INTERVAL_NS) {
            return;
        }

        if (!LAST_INFO_NS.compareAndSet(prev, now)) {
            return;
        }

        log.info("Sealed dump deferred domain={} shard={} reason={}", domainType, shard, reason);
    }
}
