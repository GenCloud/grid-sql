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

/**
 * Pure AUTH redirect decisions after {@link ServerMeta} is known (PRIMARY / READ_REPLICA).
 * <p>
 * Side effects (destroy, sticky, markStale) stay on {@link RemoteConnectionFactory}.
 * {@code applyLagStale} never ACCEPT / pin a lagging peer (sticky publish stays fail-closed).
 * PRIMARY exhausts promote/ring budget before {@link Kind#FAIL_STALE}; READ_REPLICA same.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class FactoryAuthRedirectUtil {
	/** Shared obtain / JDBC message when AUTH or ring walk exhausts on apply lag. */
	public static final String ERR_APPLY_LAG_STALE = "replica apply lag stale";

	/** Minimum writer AUTH redirect hops (promote hint). */
	public static final int WRITER_AUTH_REDIRECT_BUDGET_FLOOR = 1;

	private FactoryAuthRedirectUtil() {
	}

	/**
	 * AUTH redirect budget: READ_REPLICA walks the ring; writer walks ring (stale) with
	 * at least one promote hop.
	 */
	public static int authRedirectBudget(boolean readReplica, int endpointCount) {
		final int ringWalk = Math.max(0, endpointCount - 1);
		if (readReplica) {
			return ringWalk;
		}
		return Math.max(WRITER_AUTH_REDIRECT_BUDGET_FLOOR, ringWalk);
	}

	/**
	 * Decision after a peer reported {@code applyLagStale} (both factory roles).
	 * Never ACCEPT — rotate / promote until budget then {@link Kind#FAIL_STALE}.
	 */
	public static Outcome decideAfterApplyLagStale(int remainingAuthRedirects) {
		if (remainingAuthRedirects <= 0) {
			return Outcome.failStale();
		}
		return Outcome.retry(remainingAuthRedirects - 1);
	}

	/**
	 * PRIMARY path after {@code applyLagStale}: promote/ring budget before FAIL_STALE.
	 * {@code writerEligible} does not authorize ACCEPT of stale meta (sticky never adopts).
	 */
	public static Outcome decideAfterApplyLagStalePrimary(
			boolean writerEligible,
			int remainingAuthRedirects
	) {
		// writerEligible does not authorize ACCEPT of stale meta; budget path identical.
		if (writerEligible) {
			return decideAfterApplyLagStale(remainingAuthRedirects);
		}
		return decideAfterApplyLagStale(remainingAuthRedirects);
	}

	/**
	 * PRIMARY / writer factory decision (lag already handled by caller via
	 * {@link #decideAfterApplyLagStale(int)}).
	 *
	 * @param promoteHintIndex resolved sticky index for {@code promoteHint}, or {@code -1}
	 */
	public static Outcome decidePrimaryFresh(
			ServerMeta meta,
			int remainingAuthRedirects,
			int connectedIndex,
			int promoteHintIndex
	) {
		if (meta == null) {
			return Outcome.accept();
		}
		if (meta.writerEligible()) {
			return Outcome.accept();
		}
		if (remainingAuthRedirects <= 0 || promoteHintIndex < 0) {
			return Outcome.accept();
		}
		if (promoteHintIndex == connectedIndex) {
			return Outcome.accept();
		}
		return Outcome.retry(remainingAuthRedirects - 1);
	}

	/**
	 * AUTH redirect outcome for factory obtain paths.
	 */
	public record Outcome(Kind kind, int retryBudget) {
		public static Outcome accept() {
			return new Outcome(Kind.ACCEPT, 0);
		}

		public static Outcome failStale() {
			return new Outcome(Kind.FAIL_STALE, 0);
		}

		public static Outcome retry(int remaining) {
			return new Outcome(Kind.RETRY, remaining);
		}
	}

	/**
	 * Redirect kind after AUTH.
	 */
	public enum Kind {
		ACCEPT,
		FAIL_STALE,
		RETRY
	}
}
