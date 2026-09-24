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
package org.genfork.grid.replication.orchid;

import org.slf4j.Logger;

/**
 * Tip persistence and tip-behind message helpers for {@link OrchidNode}.
 * <p>
 * Does not touch the consensus state machine — only durable store I/O and exception text.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
final class OrchidTipPersistSupport {
	private static final String TIP_BEHIND_PEERS =
			"orchid tip behind peers; catch-up required before propose";
	private static final String TIP_BEHIND_DETAIL_PREFIX = " localTip=";
	private static final String TIP_BEHIND_PEER_PREFIX = " peerTip=";

	private OrchidTipPersistSupport() {
	}

	/** Detail message for tip-lag OrchidNotSyncedException. */
	static String tipBehindMessage(long localTip, long peerTip) {
		return TIP_BEHIND_PEERS
				+ TIP_BEHIND_DETAIL_PREFIX + localTip
				+ TIP_BEHIND_PEER_PREFIX + peerTip;
	}

	/** Best-effort persist of last committed seq (no-op when store is null). */
	static void persistCommitted(FileDurableOrchidStore durableStore, long seq, Logger log) {
		if (durableStore == null) {
			return;
		}
		try {
			durableStore.storeLastCommittedSeq(seq);
		} catch (Exception ex) {
			log.warn("Failed to persist orchid seq={}: {}", seq, ex.toString());
		}
	}
}