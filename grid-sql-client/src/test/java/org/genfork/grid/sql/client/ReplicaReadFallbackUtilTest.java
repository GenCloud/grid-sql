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
package org.genfork.grid.sql.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incomplete-replica detect for writer fallback on auto-route SELECT.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public class ReplicaReadFallbackUtilTest {
	@Test
	void matchesApplyLagStaleMessage() {
		assertTrue(ReplicaReadFallbackUtil.isIncompleteReplica(
				new IllegalStateException(FactoryAuthRedirectUtil.ERR_APPLY_LAG_STALE)));
	}

	@Test
	void walksCauseChain() {
		final RuntimeException wrapped = new RuntimeException(
				"obtain failed",
				new IllegalStateException(FactoryAuthRedirectUtil.ERR_APPLY_LAG_STALE));
		assertTrue(ReplicaReadFallbackUtil.isIncompleteReplica(wrapped));
	}

	@Test
	void ignoresUnrelated() {
		assertFalse(ReplicaReadFallbackUtil.isIncompleteReplica(new IllegalStateException("boom")));
		assertFalse(ReplicaReadFallbackUtil.isIncompleteReplica(null));
	}
}
