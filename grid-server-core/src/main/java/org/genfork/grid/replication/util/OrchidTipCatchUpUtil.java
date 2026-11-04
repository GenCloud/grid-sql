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
import org.genfork.grid.replication.orchid.OrchidNode;

/**
 * Fail-closed orchid tip catch-up after OpLog hydrate or HELLO sealed baseline.
 * <p>
 * Tip sources (only):
 * <ul>
 *   <li>{@link #journalBound(OpLog)} — max {@code OpLog.lastSeq} across streams</li>
 *   <li>observed peer tip after a completed sealed-baseline drain (retired gap)</li>
 * </ul>
 * Never advances tip from {@code max(sealed watermark)} / {@code appliedWatermark} /
 * {@code truncatedThrough} (per-stream ≠ global tip).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class OrchidTipCatchUpUtil {

	/** No sequence / no jump. */
	public static final long NO_SEQ = 0L;

	/**
	 * Minimum successful sealed packs in one drain before peer-tip catch-up is allowed.
	 */
	public static final int MIN_SEALED_PACKS_FOR_PEER_TIP = 1;

	private OrchidTipCatchUpUtil() {
	}

	/**
	 * Max live OpLog {@code lastSeq} across all streams. Not applied / sealed / truncate wm.
	 */
	public static long journalBound(OpLog opLog) {
		if (opLog == null) {
			return NO_SEQ;
		}
		long max = NO_SEQ;
		for (String streamKey : opLog.streamKeys()) {
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			final long last = opLog.lastSeq(parsed.domain(), parsed.shard());
			if (last > max) {
				max = last;
			}
		}
		return max;
	}

	/**
	 * Advance tip to {@link #journalBound(OpLog)} when the journal is ahead of local tip.
	 *
	 * @return tip after the call when a jump occurred; {@link #NO_SEQ} when no jump
	 */
	public static long catchUpFromJournal(OrchidNode orchid, OpLog opLog) {
		if (orchid == null || opLog == null) {
			return NO_SEQ;
		}
		final long bound = journalBound(opLog);
		final long localTip = orchid.getLastCommittedSeq();
		if (bound <= localTip) {
			return NO_SEQ;
		}
		orchid.advanceCommittedTip(bound);
		return bound;
	}

	/**
	 * After HELLO sealed packs were successfully applied and the pending queue is empty,
	 * advance tip to the observed peer tip when the live journal cannot cover that tip
	 * (ops retired by seal+truncate).
	 * <p>
	 * Requires {@code sealedPacksAppliedInDrain >= }{@link #MIN_SEALED_PACKS_FOR_PEER_TIP}.
	 * Does not use sealed/applied watermarks as the tip target — only the peer-advertised
	 * committed tip. When the journal already covers {@code peerTip}, delegates to
	 * {@link #catchUpFromJournal(OrchidNode, OpLog)} only.
	 *
	 * @return {@code peerTip} when jumped via peer path; {@link #NO_SEQ} otherwise
	 */
	public static long catchUpToPeerTipAfterSealedBaselineRound(
			OrchidNode orchid,
			OpLog opLog,
			long peerTip,
			int sealedPacksAppliedInDrain
	) {
		if (orchid == null
				|| sealedPacksAppliedInDrain < MIN_SEALED_PACKS_FOR_PEER_TIP
				|| peerTip <= NO_SEQ) {
			return NO_SEQ;
		}
		final long localTip = orchid.getLastCommittedSeq();
		if (peerTip <= localTip) {
			return NO_SEQ;
		}
		final long bound = journalBound(opLog);
		if (bound >= peerTip) {
			catchUpFromJournal(orchid, opLog);
			return NO_SEQ;
		}
		orchid.advanceCommittedTip(peerTip);
		return peerTip;
	}
}
