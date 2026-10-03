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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.genfork.grid.common.WriterFenceSignals;
import org.junit.jupiter.api.Test;

/**
 * Characterization: writer-fence signal matching used by Jepsen sticky rediscover.
 * <p>
 * Guards against silent rename of fence messages that would leave Clojure sticky
 * pinned to an ineligible node (calm Multi-DC :no-proposer storms). Product mid-test
 * meta/promote fix is gated on calm F/G re-stamp after settle — not weakened here.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class WriterFenceSignalsJepsenCharacterizationTest {

	@Test
	void phaseRankedProposerMessageTriggersRediscover() {
		assertTrue(WriterFenceSignals.requiresWriterRediscover(
				new IllegalStateException("SQL error 2: write requires phase-ranked proposer")));
	}

	@Test
	void orchidNotSyncedTypeAndMessageTriggerRediscover() {
		assertTrue(WriterFenceSignals.requiresWriterRediscover(
				new IllegalStateException("OrchidNotSyncedException: not synced")));
		assertTrue(WriterFenceSignals.requiresWriterRediscover(
				new IllegalStateException(
						"ORCHID NACK from a3: prevOpSeq mismatch expected=23 got=24")));
	}

	@Test
	void regionAndLearnerFencesTriggerRediscover() {
		assertTrue(WriterFenceSignals.requiresWriterRediscover(
				new IllegalStateException("region fenced: epoch advanced")));
		assertTrue(WriterFenceSignals.requiresWriterRediscover(
				new IllegalStateException("write denied on cross-dc learner")));
		assertTrue(WriterFenceSignals.requiresWriterRediscover(
				new IllegalStateException("write denied until apply lag clears")));
	}

	@Test
	void unrelatedSqlErrorDoesNotTriggerRediscover() {
		assertFalse(WriterFenceSignals.requiresWriterRediscover(
				new IllegalArgumentException("syntax error near SELECT")));
	}
}