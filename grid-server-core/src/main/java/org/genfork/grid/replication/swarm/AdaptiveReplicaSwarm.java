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
package org.genfork.grid.replication.swarm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;

/**
 * Swarm placement: sensors + hierarchical multipopulation search (placement-optimizer)
 * producing migrate plans for {@link ShardMigrator}. Threshold heuristics remain as fallback
 * when the optimizer is disabled.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public class AdaptiveReplicaSwarm {
	/** Floor for score-window ms (sensor sample period). */
	private static final long MIN_SCORE_WINDOW_MS = 100L;
	/** Ship urgency when shedding load. */
	private static final double SHIP_URGENCY_SHED_LOAD = 2.0d;
	/** Ship urgency when attracting a learner. */
	private static final double SHIP_URGENCY_ATTRACT_LEARNER = 1.5d;
	/** Ship urgency when preferring another DC. */
	private static final double SHIP_URGENCY_PREFER_DC = 1.2d;
	/** Ship urgency when keeping placement. */
	private static final double SHIP_URGENCY_KEEP = 1.0d;
	/** Composite delta above migrateThreshold → SHED_LOAD. */
	private static final double SHED_LOAD_COMPOSITE_DELTA = 0.2d;
	/** Fraction of migrateThreshold below which ATTRACT_LEARNER may fire. */
	private static final double ATTRACT_LEARNER_THRESHOLD_FRACTION = 0.5d;
	/** Hit-rate floor for ATTRACT_LEARNER. */
	private static final double ATTRACT_LEARNER_HIT_RATE_FLOOR = 0.7d;
	/** RTT ms above which PREFER_DC fires. */
	private static final double PREFER_DC_RTT_MS = 100.0d;
	/** Apply-lag normalize ceiling for composite score. */
	private static final double NORM_APPLY_LAG = 1000.0d;
	/** Queue-depth normalize ceiling for composite score. */
	private static final double NORM_QUEUE_DEPTH = 10_000.0d;
	/** RTT normalize ceiling for composite score. */
	private static final double NORM_RTT_MS = 200.0d;
	private static final double WEIGHT_APPLY_LAG = 0.35d;
	private static final double WEIGHT_QUEUE = 0.25d;
	private static final double WEIGHT_HEAP = 0.20d;
	private static final double WEIGHT_HIT_MISS = 0.10d;
	private static final double WEIGHT_RTT = 0.10d;
	private static final double DEFAULT_HIT_RATE = 1.0d;

	private final long scoreWindowMs;
	private final double migrateThreshold;
	private final HierarchicalPlacementOptimizer optimizer;
	private final SwarmMigrateHysteresis hysteresis;
	private final SwarmMigrateLoadGate loadGate = new SwarmMigrateLoadGate();
	private final AtomicBoolean migrateIoAllowed = new AtomicBoolean(false);
	private final Map<String, DoubleSupplier> sensors = new ConcurrentHashMap<>();
	private final AtomicReference<PlacementHint> lastHint = new AtomicReference<>(PlacementHint.KEEP);
	private final AtomicReference<PlacementScore> lastScore = new AtomicReference<>(
			new PlacementScore(0, 0, 0, 1, 0, 0));
	private final AtomicReference<PlacementPlan> lastPlan = new AtomicReference<>(PlacementPlan.keep());
	private final AtomicReference<VoterSetRecommendation> lastVoterRecommendation =
			new AtomicReference<>(VoterSetRecommendation.none());

	public AdaptiveReplicaSwarm(long scoreWindowMs, double migrateThreshold) {
		this(scoreWindowMs, migrateThreshold, null, null);
	}

	public AdaptiveReplicaSwarm(
			long scoreWindowMs,
			double migrateThreshold,
			HierarchicalPlacementOptimizer optimizer
	) {
		this(scoreWindowMs, migrateThreshold, optimizer, null);
	}

	public AdaptiveReplicaSwarm(
			long scoreWindowMs,
			double migrateThreshold,
			HierarchicalPlacementOptimizer optimizer,
			SwarmMigrateHysteresis hysteresis
	) {
		this.scoreWindowMs = Math.max(MIN_SCORE_WINDOW_MS, scoreWindowMs);
		this.migrateThreshold = migrateThreshold;
		this.optimizer = optimizer;
		this.hysteresis = hysteresis == null
				? new SwarmMigrateHysteresis(
						migrateThreshold,
						SwarmMigrateHysteresis.DEFAULT_ENTER_DELTA,
						SwarmMigrateHysteresis.DEFAULT_EXIT_DELTA)
				: hysteresis;
	}

	public SwarmMigrateLoadGate getLoadGate() {
		return loadGate;
	}

	public AtomicReference<PlacementHint> getLastHint() {
		return lastHint;
	}

	public AtomicReference<PlacementScore> getLastScore() {
		return lastScore;
	}

	public AtomicReference<PlacementPlan> getLastPlan() {
		return lastPlan;
	}

	public AtomicReference<VoterSetRecommendation> getLastVoterRecommendation() {
		return lastVoterRecommendation;
	}

	public void registerSensor(String name, DoubleSupplier supplier) {
		sensors.put(name, supplier);
	}

	public long scoreWindowMs() {
		return scoreWindowMs;
	}

	public boolean isMigrateIoAllowed() {
		return migrateIoAllowed.get();
	}

	/**
	 * Clear migrate I/O arm when swarmTick skips {@link #optimizePlacement} (empty peers/streams).
	 */
	public void clearMigrateIoAllowed() {
		migrateIoAllowed.set(false);
	}

	public boolean placementOptimizerEnabled() {
		return optimizer != null && optimizer.isEnabled();
	}

	/**
	 * Sample sensors, update composite score / hint. When optimizer is enabled the hint is
	 * refined by {@link #optimizePlacement(PlacementTopology)}.
	 */
	public PlacementHint tick() {
		final PlacementScore score = sampleScore();
		lastScore.set(score);
		final PlacementHint hint = thresholdHint(score);
		lastHint.set(hint);
		return hint;
	}

	/**
	 * Hierarchical DC→node search over topology; drives concrete {@link ShardMigrator} actions.
	 * When hysteresis is not armed, skip multipopulation search (hints only) to avoid CPU
	 * contention on read-heavy living gates.
	 */
	public PlacementPlan optimizePlacement(PlacementTopology topology) {
		final PlacementScore score = lastScore.get();
		final double composite = score == null ? 0.0d : score.composite();
		final PlacementHint sensorHint = score == null ? PlacementHint.KEEP : thresholdHint(score);
		// I/O gate only (sealed/OpLog migrateRange). Hysteresis still skips search when unarmed.
		migrateIoAllowed.set(loadGate.allowMigrateIo(score, sensorHint));
		if (!hysteresis.allowMigrateSearch(composite)) {
			final PlacementHint hint = score == null ? PlacementHint.KEEP : thresholdHint(score);
			final PlacementPlan cheap = new PlacementPlan(hint, List.of(), composite);
			lastPlan.set(cheap);
			lastHint.set(hint);
			return cheap;
		}
		if (optimizer == null || !optimizer.isEnabled()) {
			final PlacementPlan legacy = legacyPlan(topology, score);
			lastPlan.set(legacy);
			lastHint.set(legacy.hint());
			return legacy;
		}
		final PlacementPlan plan = optimizer.search(topology, score);
		final PlacementPlan effective = plan == null ? PlacementPlan.keep() : plan;
		lastPlan.set(effective);
		lastHint.set(effective.hint());
		if (effective.voterSet() != null) {
			lastVoterRecommendation.set(effective.voterSet());
		}
		return effective;
	}

	/** Higher urgency → smaller effective batch wait for CrossDcPublisher. */
	public double shipUrgencyMultiplier() {
		final PlacementHint hint = lastHint.get();
		return switch (hint) {
			case SHED_LOAD -> SHIP_URGENCY_SHED_LOAD;
			case ATTRACT_LEARNER -> SHIP_URGENCY_ATTRACT_LEARNER;
			case PREFER_DC -> SHIP_URGENCY_PREFER_DC;
			case KEEP -> SHIP_URGENCY_KEEP;
		};
	}

	public boolean preferCatchUp() {
		final PlacementHint hint = lastHint.get();
		return hint == PlacementHint.ATTRACT_LEARNER || hint == PlacementHint.SHED_LOAD;
	}

	private PlacementScore sampleScore() {
		final double applyLag = sensor("applyLag");
		final double queueDepth = sensor("queueDepth");
		final double heap = sensor("heapPressure");
		final double hitRate = sensor("hitRate", DEFAULT_HIT_RATE);
		final double rtt = sensor("rttMs");
		final double composite = WEIGHT_APPLY_LAG * normalize(applyLag, NORM_APPLY_LAG)
				+ WEIGHT_QUEUE * normalize(queueDepth, NORM_QUEUE_DEPTH)
				+ WEIGHT_HEAP * heap
				+ WEIGHT_HIT_MISS * (DEFAULT_HIT_RATE - hitRate)
				+ WEIGHT_RTT * normalize(rtt, NORM_RTT_MS);
		return new PlacementScore(applyLag, queueDepth, heap, hitRate, rtt, composite);
	}

	private PlacementHint thresholdHint(PlacementScore score) {
		final double composite = score.composite();
		if (composite >= migrateThreshold + SHED_LOAD_COMPOSITE_DELTA) {
			return PlacementHint.SHED_LOAD;
		}
		if (composite <= migrateThreshold * ATTRACT_LEARNER_THRESHOLD_FRACTION
				&& score.hitRate() > ATTRACT_LEARNER_HIT_RATE_FLOOR) {
			return PlacementHint.ATTRACT_LEARNER;
		}
		if (score.rttMs() > PREFER_DC_RTT_MS) {
			return PlacementHint.PREFER_DC;
		}
		return PlacementHint.KEEP;
	}

	/**
	 * Legacy path: single peer pick from threshold hint (no multipopulation search).
	 */
	private PlacementPlan legacyPlan(PlacementTopology topology, PlacementScore score) {
		final PlacementHint hint = thresholdHint(score);
		if (hint == PlacementHint.KEEP
				|| topology == null
				|| topology.peers() == null
				|| topology.peers().isEmpty()
				|| topology.streams() == null
				|| topology.streams().isEmpty()) {
			return new PlacementPlan(hint, List.of(), score == null ? 0.0 : score.composite());
		}
		final String peerId = selectLegacyPeer(hint, topology);
		if (peerId == null) {
			return new PlacementPlan(hint, List.of(), score.composite());
		}
		final ArrayList<ShardMigrateAction> actions = new ArrayList<>();
		for (PlacementTopology.StreamPlacement s : PlacementStreamFilters.needingMigrateSearch(topology)) {
			final String pin = s.affinityPin();
			final String target = pin != null ? pin : peerId;
			final String owner = s.currentOwner() == null ? topology.localNodeId() : s.currentOwner();
			if (!target.equals(owner) && !target.equals(topology.localNodeId())) {
				actions.add(new ShardMigrateAction(s.domainType(), s.shard(), target));
			}
		}
		return new PlacementPlan(hint, List.copyOf(actions), score.composite());
	}

	private static String selectLegacyPeer(PlacementHint hint, PlacementTopology topology) {
		if (hint == PlacementHint.PREFER_DC) {
			final String localDc = topology.localDc();
			for (PlacementTopology.PeerEndpoint peer : topology.peers()) {
				if (peer.dc() != null && peer.dc().equals(localDc)) {
					return peer.id();
				}
			}
		}
		if (topology.peers().isEmpty()) {
			return null;
		}
		return topology.peers().getFirst().id();
	}

	private double sensor(String name) {
		return sensor(name, 0.0);
	}

	private double sensor(String name, double defaultValue) {
		final DoubleSupplier supplier = sensors.get(name);
		if (supplier == null) {
			return defaultValue;
		}
		return supplier.getAsDouble();
	}

	private static double normalize(double value, double max) {
		if (max <= 0.0) {
			return 0.0;
		}
		return Math.min(1.0, Math.max(0.0, value / max));
	}
}
