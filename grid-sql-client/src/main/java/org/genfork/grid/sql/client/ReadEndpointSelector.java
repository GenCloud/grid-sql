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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Least-inflight pick among read endpoints with optional stale skip (atomics only).
 * <p>
 * Among equal least-inflight candidates, round-robin from {@link #tieBreakCursor} so warm-pool
 * opens and concurrent autocommit SELECTs spread across the non-stale ring (no sticky slot-0).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class ReadEndpointSelector {
	private static final int STALE_MARK = 1;
	private static final int FRESH_MARK = 0;

	private final List<HostEndpoint> endpoints;
	private final AtomicInteger cursor = new AtomicInteger(0);
	private final AtomicInteger tieBreakCursor = new AtomicInteger(0);
	private final AtomicIntegerArray inflight;
	private final AtomicIntegerArray staleMarks;

	public ReadEndpointSelector(List<HostEndpoint> endpoints) {
		if (endpoints == null || endpoints.isEmpty()) {
			throw new IllegalArgumentException("readEndpoints required");
		}
		this.endpoints = List.copyOf(endpoints);
		this.inflight = new AtomicIntegerArray(this.endpoints.size());
		this.staleMarks = new AtomicIntegerArray(this.endpoints.size());
	}

	/** Next endpoint (round-robin). Prefer {@link #acquire()} for least-inflight. */
	public HostEndpoint next() {
		final int n = endpoints.size();
		final int i = Math.floorMod(cursor.getAndIncrement(), n);
		return endpoints.get(i);
	}

	/**
	 * Least-inflight endpoint; skips stale-marked indexes when a fresh peer exists.
	 * Caller must {@link #release(HostEndpoint)} when the statement finishes.
	 */
	public HostEndpoint acquire() {
		return acquire(true);
	}

	public HostEndpoint acquire(boolean skipStaleWhenPossible) {
		final int bestIdx = pickLeastInflightIndex(skipStaleWhenPossible);
		inflight.incrementAndGet(bestIdx);
		return endpoints.get(bestIdx);
	}

	/**
	 * Least-inflight index; among ties, round-robin from {@link #tieBreakCursor}.
	 */
	private int pickLeastInflightIndex(boolean skipStaleWhenPossible) {
		final int n = endpoints.size();
		final int start = Math.floorMod(tieBreakCursor.getAndIncrement(), n);
		for (int pass = 0; pass < 2; pass++) {
			final boolean allowStale = pass == 1 || !skipStaleWhenPossible;
			int bestIdx = -1;
			int bestLoad = Integer.MAX_VALUE;
			for (int k = 0; k < n; k++) {
				final int i = (start + k) % n;
				if (!allowStale && staleMarks.get(i) == STALE_MARK) {
					continue;
				}
				final int load = inflight.get(i);
				if (load < bestLoad) {
					bestLoad = load;
					bestIdx = i;
				}
			}
			if (bestIdx >= 0) {
				return bestIdx;
			}
		}
		return Math.floorMod(cursor.getAndIncrement(), n);
	}

	public void release(HostEndpoint endpoint) {
		final int idx = indexOf(endpoint);
		if (idx >= 0) {
			inflight.updateAndGet(idx, v -> Math.max(0, v - 1));
		}
	}

	/**
	 * Count {@code endpoint} as in-flight without a pick (failover after {@link #acquire}).
	 */
	public void retain(HostEndpoint endpoint) {
		final int idx = indexOf(endpoint);
		if (idx >= 0) {
			inflight.incrementAndGet(idx);
		}
	}

	/** Mark endpoint stale after {@code REPLICA_READ_*} / applyLagStale meta. */
	public void markStale(HostEndpoint endpoint, boolean stale) {
		final int idx = indexOf(endpoint);
		if (idx >= 0) {
			staleMarks.set(idx, stale ? STALE_MARK : FRESH_MARK);
		}
	}

	/** Rotate after fail-closed replica read: mark current stale and pick another. */
	public HostEndpoint rotateAfterFailure(HostEndpoint failed) {
		markStale(failed, true);
		release(failed);
		return acquire(true);
	}

	private int indexOf(HostEndpoint endpoint) {
		if (endpoint == null) {
			return -1;
		}
		for (int i = 0; i < endpoints.size(); i++) {
			final HostEndpoint ep = endpoints.get(i);
			if (ep.host().equals(endpoint.host()) && ep.port() == endpoint.port()) {
				return i;
			}
		}
		return -1;
	}

	/** Test hook: current inflight for index. */
	int inflightAt(int index) {
		return inflight.get(index);
	}

	/** Test hook: whether index is marked stale. */
	boolean staleAt(int index) {
		return staleMarks.get(index) == STALE_MARK;
	}
}
