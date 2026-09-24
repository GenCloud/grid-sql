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
		this.scoreWindowMs = Math.max(100L, scoreWindowMs);
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
			case SHED_LOAD -> 2.0;
			case ATTRACT_LEARNER -> 1.5;
			case PREFER_DC -> 1.2;
			case KEEP -> 1.0;
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
		final double hitRate = sensor("hitRate", 1.0);
		final double rtt = sensor("rttMs");
		final double composite = 0.35 * normalize(applyLag, 1000)
				+ 0.25 * normalize(queueDepth, 10_000)
				+ 0.20 * heap
				+ 0.10 * (1.0 - hitRate)
				+ 0.10 * normalize(rtt, 200);
		return new PlacementScore(applyLag, queueDepth, heap, hitRate, rtt, composite);
	}

	private PlacementHint thresholdHint(PlacementScore score) {
		final double composite = score.composite();
		if (composite >= migrateThreshold + 0.2) {
			return PlacementHint.SHED_LOAD;
		}
		if (composite <= migrateThreshold * 0.5 && score.hitRate() > 0.7) {
			return PlacementHint.ATTRACT_LEARNER;
		}
		if (score.rttMs() > 100) {
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
		final java.util.ArrayList<ShardMigrateAction> actions = new java.util.ArrayList<>();
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
