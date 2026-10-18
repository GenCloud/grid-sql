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

import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Fail-closed gates for sealed dump so mid-TX / mid-envelope merge cannot
 * stamp dirty staging into sealed and truncate OpLog past an open unit.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class SealedDumpSafetyUtil {
    private static final Logger log = LoggerFactory.getLogger(SealedDumpSafetyUtil.class);

    /** Min interval between INFO lines for deferred sealed dumps. */
    private static final long MIN_INFO_INTERVAL_NS = 5_000_000_000L;
    private static final AtomicLong LAST_INFO_NS = new AtomicLong();

	private SealedDumpSafetyUtil() {
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
		if (envelope != null && envelope.hasOpenApplyStagingForStream(domainType, shard)) {
			return DeferReason.ENVELOPE;
		}

		return DeferReason.NONE;
	}

	/**
	 * Gate adapter for {@link SealedGridMapService#dumpDomain}.
	 */
	public static SealedGridMapService.DumpDeferGate deferGate(ReplicaApplier applier) {
		return (domainType, shard) -> {
			final DeferReason reason = deferReason(applier, domainType, shard);
			if (reason == DeferReason.NONE) {
				return false;
			}

			onShardDeferred(domainType, shard, reason);
			return true;
		};
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
