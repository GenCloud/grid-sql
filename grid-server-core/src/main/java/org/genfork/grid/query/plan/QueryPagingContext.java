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
package org.genfork.grid.query.plan;

/**
 * LIMIT/offset for index EQ/prefix early-stop via {@link ScopedValue} (no ThreadLocal).
 * <p>
 * Early-stop is safe for unordered EQ+LIMIT and for composite prefix scans whose leaf order
 * already matches {@code ORDER BY}. Callers must omit paging when ORDER BY needs a full
 * candidate set before sort (TD-PERF-002).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public final class QueryPagingContext {
	static final ScopedValue<PagingData> PAGING = ScopedValue.newInstance();

	private QueryPagingContext() {
	}

	public static <T> T callWithPaging(PagingData paging, java.util.concurrent.Callable<T> action) {
		try {
			if (paging == null) {
				return action.call();
			}
			return ScopedValue.where(PAGING, paging).call(() -> {
				try {
					return action.call();
				} catch (RuntimeException re) {
					throw re;
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
		} catch (RuntimeException re) {
			throw re;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Filter-phase paging: {@code null} when ORDER BY requires collecting all EQ matches
	 * before an external sort (unordered index leaf walk).
	 *
	 * @param paging                          statement LIMIT/OFFSET
	 * @param orderByRequiresFullCandidates   true when ORDER BY is present but filter leaf
	 *                                        order does not already satisfy it
	 */
	public static PagingData forFilterEarlyStop(PagingData paging, boolean orderByRequiresFullCandidates) {
		if (orderByRequiresFullCandidates) {
			return null;
		}
		return paging;
	}

	/**
	 * Max pointers to collect: {@code offset + limit}, or 0 when unlimited / unbound.
	 */
	public static int maxPointersHint() {
		if (!PAGING.isBound()) {
			return 0;
		}
		final PagingData p = PAGING.get();
		if (p == null || p.limit() <= 0) {
			return 0;
		}
		return Math.max(0, p.offset()) + p.limit();
	}
}
