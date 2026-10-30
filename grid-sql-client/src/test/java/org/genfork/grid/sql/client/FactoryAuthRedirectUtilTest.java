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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * AUTH redirect pure decisions ({@code applyLagStale} fail-closed).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public class FactoryAuthRedirectUtilTest {
	private static final String NODE = "n1";
	private static final long SCHEMA_EPOCH = 1L;
	private static final int CONNECTED_INDEX = 0;
	private static final int HINT_INDEX = 1;
	private static final int REDIRECT_BUDGET = 2;

	@Test
	void applyLagStaleExhaustsToFailStale() {
		final FactoryAuthRedirectUtil.Outcome exhausted =
				FactoryAuthRedirectUtil.decideAfterApplyLagStale(0);
		assertSame(FactoryAuthRedirectUtil.Kind.FAIL_STALE, exhausted.kind());

		final FactoryAuthRedirectUtil.Outcome retry =
				FactoryAuthRedirectUtil.decideAfterApplyLagStale(REDIRECT_BUDGET);
		assertSame(FactoryAuthRedirectUtil.Kind.RETRY, retry.kind());
		assertEquals(REDIRECT_BUDGET - 1, retry.retryBudget());
	}

	@Test
	void primaryFreshWriterEligibleAccepts() {
		final ServerMeta writer = new ServerMeta(NODE, true, "", false, SCHEMA_EPOCH);
		final FactoryAuthRedirectUtil.Outcome outcome = FactoryAuthRedirectUtil.decidePrimaryFresh(
				writer, REDIRECT_BUDGET, CONNECTED_INDEX, HINT_INDEX);
		assertSame(FactoryAuthRedirectUtil.Kind.ACCEPT, outcome.kind());
	}

	@Test
	void primaryFreshIneligiblePromotesWhenHintDiffers() {
		final ServerMeta follower = new ServerMeta(NODE, false, "n2", false, SCHEMA_EPOCH);
		final FactoryAuthRedirectUtil.Outcome outcome = FactoryAuthRedirectUtil.decidePrimaryFresh(
				follower, REDIRECT_BUDGET, CONNECTED_INDEX, HINT_INDEX);
		assertSame(FactoryAuthRedirectUtil.Kind.RETRY, outcome.kind());
		assertEquals(REDIRECT_BUDGET - 1, outcome.retryBudget());
	}

	@Test
	void primaryFreshIneligibleAcceptsWithoutHintBudget() {
		final ServerMeta follower = new ServerMeta(NODE, false, "", false, SCHEMA_EPOCH);
		final FactoryAuthRedirectUtil.Outcome outcome = FactoryAuthRedirectUtil.decidePrimaryFresh(
				follower, 0, CONNECTED_INDEX, -1);
		assertSame(FactoryAuthRedirectUtil.Kind.ACCEPT, outcome.kind());
	}

	@Test
	void authRedirectBudgetWriterWalksRing() {
		final int endpoints = 3;
		assertEquals(
				endpoints - 1,
				FactoryAuthRedirectUtil.authRedirectBudget(false, endpoints));
		assertEquals(
				FactoryAuthRedirectUtil.WRITER_AUTH_REDIRECT_BUDGET_FLOOR,
				FactoryAuthRedirectUtil.authRedirectBudget(false, 1));
		assertEquals(endpoints - 1, FactoryAuthRedirectUtil.authRedirectBudget(true, endpoints));
	}
}
