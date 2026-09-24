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
package org.genfork.grid.mem.adaptive;

import org.genfork.grid.mem.stage.WorkingSetBudget;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.threading.ThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodic adaptive disk-first controller: samples heap / WS pressure via atomics and
 * applies LOW/NORMAL/HIGH hysteresis to {@link WorkingSetBudget} caps.
 * <p>
 * Runs on logic VT (not Netty EL). Sealed GMAP + OpLog remain SoT — this only tunes
 * the RAM working-set accelerator under memory pressure.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class AdaptiveDiskFirstController {
	private static final Logger log = LoggerFactory.getLogger(AdaptiveDiskFirstController.class);

	/** Default WS ceiling when {@code workingSetMaxEntries=0} and adaptive is on. */
	public static final int DEFAULT_CONFIGURED_MAX = 65_536;

	private static final double HIGH_ENTER_FREE = 0.20;
	private static final double HIGH_EXIT_FREE = 0.28;
	private static final double LOW_ENTER_FREE = 0.40;
	private static final double LOW_EXIT_FREE = 0.32;
	private static final double LOW_ENTER_WS_FILL = 0.50;
	private static final double LOW_EXIT_WS_FILL = 0.65;
	private static final long TICK_MS = 200L;
	/** HIGH mode working-set cap divisor vs configured ceiling. */
	private static final int HIGH_CAP_DIVISOR = 8;
	/**
	 * NORMAL shares the configured ceiling with LOW. Only {@link AdaptiveDiskFirstMode#HIGH}
	 * shrinks the cap — a prior NORMAL=/2 cut mid-Capacity (LOW fill→NORMAL) mass-evicted
	 * tens of thousands of hot keys while heap was still healthy.
	 */
	private static final int NORMAL_CAP_DIVISOR = 1;

	private final int configuredMaxEntries;
	private final boolean configuredLazyHydrate;
	private final AtomicReference<AdaptiveDiskFirstMode> mode =
			new AtomicReference<>(AdaptiveDiskFirstMode.NORMAL);
	private final AtomicInteger effectiveMaxEntries;
	private final AtomicLong sealedMissSnapshot = new AtomicLong();
	private final List<WorkingSetBudget> budgets = new CopyOnWriteArrayList<>();
	private final MemoryMXBean memoryMXBean;
	private volatile ScheduledFuture<?> tickFuture;
	private volatile Double freeRatioOverride;

	public AdaptiveDiskFirstController(int workingSetMaxEntries, boolean configuredLazyHydrate) {
		this.configuredMaxEntries = workingSetMaxEntries > 0
				? workingSetMaxEntries
				: DEFAULT_CONFIGURED_MAX;
		this.configuredLazyHydrate = configuredLazyHydrate;
		this.effectiveMaxEntries = new AtomicInteger(capFor(AdaptiveDiskFirstMode.NORMAL));
		this.memoryMXBean = ManagementFactory.getMemoryMXBean();
	}

	public AdaptiveDiskFirstMode mode() {
		return mode.get();
	}

	public int effectiveMaxEntries() {
		return effectiveMaxEntries.get();
	}

	/**
	 * True when HIGH (or configured LAZY floor): shard-touch hydrate, no FULL preload expectation.
	 */
	public boolean forceLazySemantics() {
		return configuredLazyHydrate || mode.get() == AdaptiveDiskFirstMode.HIGH;
	}

	public boolean preferSealedOnlyReads() {
		return mode.get() == AdaptiveDiskFirstMode.HIGH;
	}

	public void registerBudget(WorkingSetBudget budget) {
		if (budget == null) {
			return;
		}
		budgets.add(budget);
		applyToBudget(budget, mode.get(), effectiveMaxEntries.get());
	}

	public void start() {
		if (tickFuture != null) {
			return;
		}
		tickFuture = ThreadService.getScheduledExecutor().scheduleAtFixedRate(
				() -> ThreadService.getLogicExecutor().execute(this::tickSafe),
				TICK_MS,
				TICK_MS,
				TimeUnit.MILLISECONDS
		);
		log.info("AdaptiveDiskFirst started configuredMax={} hydrateLazyFloor={} mode={}",
				configuredMaxEntries, configuredLazyHydrate, mode.get());
	}

	public void stop() {
		final ScheduledFuture<?> f = tickFuture;
		tickFuture = null;
		if (f != null) {
			f.cancel(false);
		}
	}

	/**
	 * After DROP / domain purge: clear sealed-only preference and restore LOW ceiling.
	 * Atomically relaxes mode so the next Capacity seed is not pinned in HIGH from a
	 * prior warm write that filled the NORMAL budget while heap free-ratio stayed healthy.
	 */
	public void relaxAfterDomainPurge() {
		final AdaptiveDiskFirstMode current = mode.get();
		if (current == AdaptiveDiskFirstMode.LOW) {
			return;
		}
		if (mode.compareAndSet(current, AdaptiveDiskFirstMode.LOW)) {
			final int cap = capFor(AdaptiveDiskFirstMode.LOW);
			effectiveMaxEntries.set(cap);
			applyAll(AdaptiveDiskFirstMode.LOW, cap);
			ReplicationMetrics.recordAdaptiveModeChange();
			log.info("AdaptiveDiskFirst relaxAfterDomainPurge {} → LOW effectiveMax={}", current, cap);
		}
	}

	/** Test / ops: force free-ratio sample ({@code null} = live MemoryMXBean). */
	public void setFreeRatioOverride(Double freeRatio) {
		this.freeRatioOverride = freeRatio;
	}

	/** Evaluate pressure and apply mode (logic VT / test harness). */
	public void tick() {
		final double freeRatio = sampleHeapFreeRatio();
		int wsSize = 0;
		for (WorkingSetBudget b : budgets) {
			wsSize += b.size();
		}
		final int wsCap = Math.max(1, effectiveMaxEntries.get());
		final long sealedMisses = ReplicationMetrics.sealedMisses();
		final long prev = sealedMissSnapshot.getAndSet(sealedMisses);
		final long sealedMissDelta = Math.max(0L, sealedMisses - prev);
		evaluate(freeRatio, wsSize, wsCap, sealedMissDelta);
	}

	/**
	 * Pure hysteresis evaluation (atomics only — no monitors). Package API for IT.
	 * <p>
	 * HIGH requires real heap free-ratio pressure — not working-set fill and not
	 * sealed-miss bursts alone. Fill is already enforced by {@link WorkingSetBudget}
	 * caps; fill≥90%→HIGH previously collapsed the cap and forced sealed-only reads
	 * on a healthy heap. Miss bursts on a sparse Capacity key-space (1 M KEY_SPACE /
	 * 1 k seed) similarly pinned HIGH via {@code preferSealedOnlyReads} and crushed
	 * READ_ONLY (~10 k vs ~46 k+). Miss pressure with healthy heap only labels NORMAL.
	 */
	public void evaluate(double heapFreeRatio, int wsEntries, int wsCap, long sealedMissDelta) {
		final AdaptiveDiskFirstMode current = mode.get();
		final double fill = wsCap <= 0 ? 0.0 : (double) wsEntries / (double) wsCap;
		final boolean heapHigh = heapFreeRatio < HIGH_ENTER_FREE;
		final boolean heapLowOk = heapFreeRatio >= LOW_ENTER_FREE;

		AdaptiveDiskFirstMode next = current;
		switch (current) {
			case HIGH -> {
				// Exit when heap recovers; do not require fill drain or miss silence
				// (fill stays high under a tight HIGH cap and would pin HIGH forever;
				// Capacity sparse EQ keeps producing sealed-miss ticks indefinitely).
				if (heapFreeRatio >= HIGH_EXIT_FREE) {
					next = heapLowOk && fill <= LOW_ENTER_WS_FILL
							? AdaptiveDiskFirstMode.LOW
							: AdaptiveDiskFirstMode.NORMAL;
				}
			}
			case LOW -> {
				if (heapHigh) {
					next = AdaptiveDiskFirstMode.HIGH;
				} else if (heapFreeRatio < LOW_EXIT_FREE) {
					next = AdaptiveDiskFirstMode.NORMAL;
				} else if (fill > LOW_EXIT_WS_FILL) {
					// Busy WS with healthy heap → NORMAL label only (same ceiling as LOW).
					next = AdaptiveDiskFirstMode.NORMAL;
				}
				// sealed-miss bursts: ignore — Capacity sparse EQ would flap LOW↔NORMAL
				// every tick and thrash applyAll without changing the ceiling.
			}
			case NORMAL -> {
				if (heapHigh) {
					next = AdaptiveDiskFirstMode.HIGH;
				} else if (heapLowOk && fill <= LOW_ENTER_WS_FILL) {
					next = AdaptiveDiskFirstMode.LOW;
				}
			}
			default -> {
			}
		}

		final int newCap = capFor(next);
		if (next != current) {
			if (mode.compareAndSet(current, next)) {
				effectiveMaxEntries.set(newCap);
				applyAll(next, newCap);
				ReplicationMetrics.recordAdaptiveModeChange();
				log.info("AdaptiveDiskFirst mode {} → {} (LOW=relaxed WS, HIGH=pressure) effectiveMax={} freeRatio={} wsFill={} sealedMissDelta={}",
						current, next, newCap, String.format("%.3f", heapFreeRatio),
						String.format("%.3f", fill), sealedMissDelta);
			}
		} else if (effectiveMaxEntries.get() != newCap) {
			effectiveMaxEntries.set(newCap);
			applyAll(next, newCap);
		}
	}

	private void tickSafe() {
		try {
			tick();
		} catch (RuntimeException ex) {
			log.warn("AdaptiveDiskFirst tick failed: {}", ex.toString());
		}
	}

	private double sampleHeapFreeRatio() {
		final Double override = freeRatioOverride;
		if (override != null) {
			return Math.max(0.0, Math.min(1.0, override));
		}
		final MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
		final long max = heap.getMax();
		if (max <= 0L) {
			final long committed = heap.getCommitted();
			if (committed <= 0L) {
				return 1.0;
			}
			return 1.0 - ((double) heap.getUsed() / (double) committed);
		}
		return 1.0 - ((double) heap.getUsed() / (double) max);
	}

	private int capFor(AdaptiveDiskFirstMode m) {
		final int ceiling = configuredMaxEntries;
		return switch (m) {
			case HIGH -> Math.max(1, ceiling / HIGH_CAP_DIVISOR);
			case NORMAL -> Math.max(1, ceiling / NORMAL_CAP_DIVISOR);
			case LOW -> ceiling;
		};
	}

	private void applyAll(AdaptiveDiskFirstMode m, int cap) {
		for (WorkingSetBudget budget : budgets) {
			applyToBudget(budget, m, cap);
		}
	}

	private void applyToBudget(WorkingSetBudget budget, AdaptiveDiskFirstMode m, int cap) {
		budget.setMaxEntries(cap);
		budget.setPreferSealedOnly(m == AdaptiveDiskFirstMode.HIGH);
	}
}
