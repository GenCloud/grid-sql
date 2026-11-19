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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Coalesced APPLY_NACK → catch-up/repair scheduling (no tip mint; off Netty EL).
 * <p>
 * APPLY_NACK may fan-out to all peers; this util enforces one heal per
 * {@code domain#shard} per interval and caps concurrent sealed packs.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class ApplyNackHealUtil {

	/** Minimum interval between heal schedules for the same stream. */
	public static final long APPLY_NACK_HEAL_INTERVAL_MS = 500L;

	/** Max concurrent sealed-pack heal actions cluster-wide in this process. */
	public static final int MAX_CONCURRENT_PACKS = 2;

	private ApplyNackHealUtil() {
	}

	/**
	 * Mutable coalesce state shared by the inbound NACK path.
	 */
	public static final class CoalesceState {
		private final ConcurrentHashMap<String, Long> lastHealMillisByStream = new ConcurrentHashMap<>();
		private final ConcurrentHashMap<String, Long> lastHealedSeqByStream = new ConcurrentHashMap<>();
		private final AtomicInteger inflightPacks = new AtomicInteger();

		public ConcurrentHashMap<String, Long> lastHealMillisByStream() {
			return lastHealMillisByStream;
		}

		public ConcurrentHashMap<String, Long> lastHealedSeqByStream() {
			return lastHealedSeqByStream;
		}

		public AtomicInteger inflightPacks() {
			return inflightPacks;
		}
	}

	/**
	 * Whether a heal should run for this NACK (coalesce / suppress / pack cap).
	 *
	 * @param peerAckAtOrAboveNack when peer already acked ≥ nack seq → suppress
	 * @param wantsPack            sealed pack path (counts against pack cap)
	 */
	public static boolean shouldScheduleHeal(
			CoalesceState state,
			String domainType,
			int shard,
			long nackOpSeq,
			long nowMillis,
			long intervalMs,
			boolean peerAckAtOrAboveNack,
			boolean wantsPack
	) {
		Objects.requireNonNull(state, "state");
		if (domainType == null || nackOpSeq <= 0L) {
			return false;
		}
		if (peerAckAtOrAboveNack) {
			return false;
		}
		final String key = OpLogStreamKeyUtil.format(domainType, shard);
		final Long healedSeq = state.lastHealedSeqByStream().get(key);
		if (healedSeq != null && healedSeq.longValue() >= nackOpSeq) {
			return false;
		}
		final long interval = intervalMs <= 0L ? APPLY_NACK_HEAL_INTERVAL_MS : intervalMs;
		final Long last = state.lastHealMillisByStream().get(key);
		if (last != null && (nowMillis - last.longValue()) < interval) {
			return false;
		}
		if (wantsPack) {
			final int inflight = state.inflightPacks().get();
			if (inflight >= MAX_CONCURRENT_PACKS) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Mark heal scheduled; caller runs action on logic executor.
	 */
	public static void markScheduled(
			CoalesceState state,
			String domainType,
			int shard,
			long nackOpSeq,
			long nowMillis,
			boolean wantsPack
	) {
		Objects.requireNonNull(state, "state");
		final String key = OpLogStreamKeyUtil.format(domainType, shard);
		state.lastHealMillisByStream().put(key, nowMillis);
		state.lastHealedSeqByStream().merge(key, nackOpSeq, Math::max);
		if (wantsPack) {
			state.inflightPacks().incrementAndGet();
		}
	}

	/**
	 * Release pack slot after sealed heal completes (success or fail).
	 */
	public static void releasePackSlot(CoalesceState state) {
		if (state == null) {
			return;
		}
		state.inflightPacks().updateAndGet(v -> Math.max(0, v - 1));
	}

	/**
	 * Schedule on executor when {@link #shouldScheduleHeal} passes; otherwise invoke {@code onSuppressed}.
	 */
	public static void trySchedule(
			CoalesceState state,
			String domainType,
			int shard,
			long nackOpSeq,
			long nowMillis,
			boolean peerAckAtOrAboveNack,
			boolean wantsPack,
			Consumer<Runnable> logicExecutor,
			Runnable healAction,
			Runnable onSuppressed
	) {
		if (!shouldScheduleHeal(
				state, domainType, shard, nackOpSeq, nowMillis,
				APPLY_NACK_HEAL_INTERVAL_MS, peerAckAtOrAboveNack, wantsPack)) {
			if (onSuppressed != null) {
				onSuppressed.run();
			}
			return;
		}
		markScheduled(state, domainType, shard, nackOpSeq, nowMillis, wantsPack);
		final Runnable wrapped = () -> {
			try {
				if (healAction != null) {
					healAction.run();
				}
			} finally {
				if (wantsPack) {
					releasePackSlot(state);
				}
			}
		};
		if (logicExecutor == null) {
			wrapped.run();
			return;
		}
		logicExecutor.accept(wrapped);
	}
}
