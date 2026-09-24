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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hierarchical multipopulation placement search (HDCRM-class):
 * coarse DC assignment population → fine node/shard population → migrate plan.
 * Public config surface is {@code placement-optimizer} / {@code swarm}, not {@code hdcrm.*}.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public class HierarchicalPlacementOptimizer {
	private final boolean enabled;
	private final int dcPopulationSize;
	private final int nodePopulationSize;
	private final int generationsPerTick;
	private final double mutationRate;
	private final double diversityWeight;
	private final int maxMigratesPerTick;
	private final long seed;
	private final double migrateThreshold;
	/** Idle-churn floor (hysteresis exit); search may run when composite >= this. */
	private final double migrateSearchFloor;
	private final boolean voterSetHintsEnabled;
	private final int targetRemoteVoters;

	public HierarchicalPlacementOptimizer(
			boolean enabled,
			int dcPopulationSize,
			int nodePopulationSize,
			int generationsPerTick,
			double mutationRate,
			double diversityWeight,
			int maxMigratesPerTick,
			long seed,
			double migrateThreshold
	) {
		this(enabled, dcPopulationSize, nodePopulationSize, generationsPerTick, mutationRate,
				diversityWeight, maxMigratesPerTick, seed, migrateThreshold, migrateThreshold, true, 1);
	}

	public HierarchicalPlacementOptimizer(
			boolean enabled,
			int dcPopulationSize,
			int nodePopulationSize,
			int generationsPerTick,
			double mutationRate,
			double diversityWeight,
			int maxMigratesPerTick,
			long seed,
			double migrateThreshold,
			boolean voterSetHintsEnabled,
			int targetRemoteVoters
	) {
		this(enabled, dcPopulationSize, nodePopulationSize, generationsPerTick, mutationRate,
				diversityWeight, maxMigratesPerTick, seed, migrateThreshold, migrateThreshold,
				voterSetHintsEnabled, targetRemoteVoters);
	}

	public HierarchicalPlacementOptimizer(
			boolean enabled,
			int dcPopulationSize,
			int nodePopulationSize,
			int generationsPerTick,
			double mutationRate,
			double diversityWeight,
			int maxMigratesPerTick,
			long seed,
			double migrateThreshold,
			double migrateSearchFloor,
			boolean voterSetHintsEnabled,
			int targetRemoteVoters
	) {
		this.enabled = enabled;
		this.dcPopulationSize = Math.max(2, dcPopulationSize);
		this.nodePopulationSize = Math.max(2, nodePopulationSize);
		this.generationsPerTick = Math.max(1, generationsPerTick);
		this.mutationRate = Math.min(1.0, Math.max(0.0, mutationRate));
		this.diversityWeight = Math.min(1.0, Math.max(0.0, diversityWeight));
		this.maxMigratesPerTick = Math.max(1, maxMigratesPerTick);
		this.seed = seed;
		this.migrateThreshold = migrateThreshold;
		this.migrateSearchFloor = Math.min(migrateThreshold, Math.max(0.0, migrateSearchFloor));
		this.voterSetHintsEnabled = voterSetHintsEnabled;
		this.targetRemoteVoters = Math.max(1, targetRemoteVoters);
	}

	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Coarse→fine search. Fitness is a cost (lower is better).
	 * Returns no migrations when sensor composite is below migrateThreshold (avoid idle churn).
	 */
	public PlacementPlan search(PlacementTopology topology, PlacementScore sensors) {
		final VoterSetRecommendation voterHint = voterSetHintsEnabled
				? recommendRemoteVoters(topology, sensors)
				: VoterSetRecommendation.none();
		if (!enabled || topology == null || topology.peers() == null || topology.peers().isEmpty()) {
			return new PlacementPlan(PlacementHint.KEEP, List.of(), 0.0, voterHint);
		}
		final PlacementHint sensorHint = hintFromSensors(sensors);
		// Avoid idle placement churn: only search/migrate when composite is above search floor.
		if (sensors != null && sensors.composite() < migrateSearchFloor) {
			return new PlacementPlan(sensorHint, List.of(), sensors.composite(), voterHint);
		}
		final List<PlacementTopology.StreamPlacement> movable = movableStreams(topology);
		if (movable.isEmpty()) {
			return new PlacementPlan(sensorHint, List.of(), sensors == null ? 0.0 : sensors.composite(), voterHint);
		}

		final Random rng = seed == 0L
				? ThreadLocalRandom.current()
				: new Random(seed);

		final List<String> dcs = distinctDcs(topology);
		final Map<String, List<String>> peersByDc = peersByDc(topology);

		final List<Map<String, String>> dcPop = initDcPopulation(movable, dcs, topology, rng);
		evolveDcPopulation(dcPop, movable, dcs, sensors, topology, rng);

		final Map<String, String> eliteDc = bestDcIndividual(dcPop, movable, sensors, topology);
		final List<Map<String, String>> nodePop = initNodePopulation(movable, eliteDc, peersByDc, topology, rng);
		evolveNodePopulation(nodePop, movable, eliteDc, peersByDc, sensors, topology, rng);

		final Map<String, String> eliteNode = bestNodeIndividual(nodePop, movable, sensors, topology);
		final List<ShardMigrateAction> migrations = toMigrations(movable, eliteNode, topology);
		final double fitness = nodeFitness(eliteNode, movable, sensors, topology);
		final PlacementHint hint = migrations.isEmpty()
				? hintFromSensors(sensors)
				: hintFromPlan(sensors, migrations, topology);
		return new PlacementPlan(hint, List.copyOf(migrations), fitness, voterHint);
	}

	/**
	 * Coarse population over remote-DC peer subsets as candidate digest voters.
	 * Output is a hint only — never silently rewrites live ORCHID quorum.
	 */
	public VoterSetRecommendation recommendRemoteVoters(PlacementTopology topology, PlacementScore sensors) {
		if (topology == null || topology.peers() == null || topology.peers().isEmpty()) {
			return VoterSetRecommendation.none();
		}
		final String localDc = topology.localDc() == null ? "dc-local" : topology.localDc();
		final List<PlacementTopology.PeerEndpoint> remoteCandidates = new ArrayList<>();
		for (PlacementTopology.PeerEndpoint p : topology.peers()) {
			if (p == null || p.id() == null) {
				continue;
			}
			final String dc = p.dc() == null ? localDc : p.dc();
			if (!dc.equals(localDc)) {
				remoteCandidates.add(p);
			}
		}
		if (remoteCandidates.isEmpty()) {
			return VoterSetRecommendation.none();
		}

		final Random rng = seed == 0L ? ThreadLocalRandom.current() : new Random(seed ^ 0x5F3759DF);
		final int k = Math.min(targetRemoteVoters, remoteCandidates.size());
		final List<List<String>> population = new ArrayList<>(dcPopulationSize);
		// Prefer current VOTER-tagged remotes as elite seed.
		final List<String> currentVoters = new ArrayList<>();
		for (PlacementTopology.PeerEndpoint p : remoteCandidates) {
			if (p.role() == PeerRole.VOTER) {
				currentVoters.add(p.id());
			}
		}
		if (currentVoters.size() >= k) {
			population.add(List.copyOf(currentVoters.subList(0, k)));
		} else if (!currentVoters.isEmpty()) {
			final List<String> seedSet = new ArrayList<>(currentVoters);
			while (seedSet.size() < k) {
				final String id = remoteCandidates.get(rng.nextInt(remoteCandidates.size())).id();
				if (!seedSet.contains(id)) {
					seedSet.add(id);
				}
			}
			population.add(seedSet);
		}
		while (population.size() < dcPopulationSize) {
			population.add(randomSubset(remoteCandidates, k, rng));
		}

		List<String> elite = population.getFirst();
		double best = voterFitness(elite, remoteCandidates, sensors);
		for (int gen = 0; gen < generationsPerTick; gen++) {
			for (int i = 0; i < population.size(); i++) {
				List<String> ind = population.get(i);
				if (rng.nextDouble() < mutationRate) {
					ind = mutateSubset(ind, remoteCandidates, k, rng);
					population.set(i, ind);
				}
				final double fit = voterFitness(ind, remoteCandidates, sensors);
				if (fit < best) {
					best = fit;
					elite = ind;
				}
			}
			population.set(0, elite);
		}
		return new VoterSetRecommendation(List.copyOf(elite), best);
	}

	private static List<String> randomSubset(
			List<PlacementTopology.PeerEndpoint> candidates,
			int k,
			Random rng
	) {
		final List<String> ids = new ArrayList<>(candidates.size());
		for (PlacementTopology.PeerEndpoint p : candidates) {
			ids.add(p.id());
		}
		final List<String> out = new ArrayList<>(k);
		while (out.size() < k && !ids.isEmpty()) {
			final int idx = rng.nextInt(ids.size());
			out.add(ids.remove(idx));
		}
		return out;
	}

	private static List<String> mutateSubset(
			List<String> current,
			List<PlacementTopology.PeerEndpoint> candidates,
			int k,
			Random rng
	) {
		final List<String> next = new ArrayList<>(current);
		if (!next.isEmpty()) {
			next.remove(rng.nextInt(next.size()));
		}
		while (next.size() < k) {
			final String id = candidates.get(rng.nextInt(candidates.size())).id();
			if (!next.contains(id)) {
				next.add(id);
			}
		}
		return next;
	}

	private double voterFitness(
			List<String> subset,
			List<PlacementTopology.PeerEndpoint> candidates,
			PlacementScore sensors
	) {
		final double rtt = sensors == null ? 50.0 : sensors.rttMs();
		final double lag = sensors == null ? 0.0 : sensors.applyLag();
		final double heap = sensors == null ? 0.0 : sensors.heapPressure();
		double score = 0.0;
		for (String id : subset) {
			PeerRole role = PeerRole.LEARNER;
			for (PlacementTopology.PeerEndpoint p : candidates) {
				if (id.equals(p.id())) {
					role = p.role() == null ? PeerRole.LEARNER : p.role();
					break;
				}
			}
			// Prefer already-tagged voters; penalize promoting learners under load.
			final double roleTax = role == PeerRole.VOTER ? 0.0 : 0.15;
			score += 0.45 * normalize(rtt, 200)
					+ 0.25 * normalize(lag, 1000)
					+ 0.15 * heap
					+ 0.15 * roleTax;
		}
		// Write-tax estimate: each remote voter adds WAN digest round-trip cost.
		score += 0.20 * subset.size() * normalize(rtt, 200);
		return score;
	}

	private List<PlacementTopology.StreamPlacement> movableStreams(PlacementTopology topology) {
		// Skip streams already shed to a peer (settled) — avoids multipopulation CPU every swarm tick.
		return PlacementStreamFilters.needingMigrateSearch(topology);
	}

	private List<String> distinctDcs(PlacementTopology topology) {
		final List<String> dcs = new ArrayList<>();
		final String local = topology.localDc() == null ? "dc-local" : topology.localDc();
		dcs.add(local);
		for (PlacementTopology.PeerEndpoint p : topology.peers()) {
			final String dc = p.dc() == null ? local : p.dc();
			if (!dcs.contains(dc)) {
				dcs.add(dc);
			}
		}
		return dcs;
	}

	private Map<String, List<String>> peersByDc(PlacementTopology topology) {
		final Map<String, List<String>> map = new LinkedHashMap<>();
		final String localDc = topology.localDc() == null ? "dc-local" : topology.localDc();
		for (PlacementTopology.PeerEndpoint p : topology.peers()) {
			final String dc = p.dc() == null ? localDc : p.dc();
			map.computeIfAbsent(dc, k -> new ArrayList<>()).add(p.id());
		}
		return map;
	}

	private List<Map<String, String>> initDcPopulation(
			List<PlacementTopology.StreamPlacement> streams,
			List<String> dcs,
			PlacementTopology topology,
			Random rng
	) {
		final List<Map<String, String>> pop = new ArrayList<>(dcPopulationSize);
		pop.add(currentDcAssignment(streams, topology));
		while (pop.size() < dcPopulationSize) {
			final Map<String, String> ind = new HashMap<>();
			for (PlacementTopology.StreamPlacement s : streams) {
				ind.put(s.streamKey(), dcs.get(rng.nextInt(dcs.size())));
			}
			pop.add(ind);
		}
		return pop;
	}

	private Map<String, String> currentDcAssignment(
			List<PlacementTopology.StreamPlacement> streams,
			PlacementTopology topology
	) {
		final Map<String, String> ind = new HashMap<>();
		final String localDc = topology.localDc() == null ? "dc-local" : topology.localDc();
		final Map<String, String> peerDc = new HashMap<>();
		for (PlacementTopology.PeerEndpoint p : topology.peers()) {
			peerDc.put(p.id(), p.dc() == null ? localDc : p.dc());
		}
		for (PlacementTopology.StreamPlacement s : streams) {
			final String owner = effectiveOwner(s, topology.localNodeId());
			if (owner.equals(topology.localNodeId())) {
				ind.put(s.streamKey(), localDc);
			} else {
				ind.put(s.streamKey(), peerDc.getOrDefault(owner, localDc));
			}
		}
		return ind;
	}

	private void evolveDcPopulation(
			List<Map<String, String>> pop,
			List<PlacementTopology.StreamPlacement> streams,
			List<String> dcs,
			PlacementScore sensors,
			PlacementTopology topology,
			Random rng
	) {
		for (int gen = 0; gen < generationsPerTick; gen++) {
			final Map<String, String> elite = bestDcIndividual(pop, streams, sensors, topology);
			for (int i = 0; i < pop.size(); i++) {
				Map<String, String> ind = pop.get(i);
				if (rng.nextDouble() < mutationRate) {
					ind = mutateDc(ind, streams, dcs, rng);
				}
				if (rng.nextDouble() < diversityWeight) {
					ind = migrateFromElite(ind, elite, streams, rng);
				}
				pop.set(i, ind);
			}
			// keep elite
			pop.set(0, elite);
		}
	}

	private Map<String, String> mutateDc(
			Map<String, String> ind,
			List<PlacementTopology.StreamPlacement> streams,
			List<String> dcs,
			Random rng
	) {
		final Map<String, String> next = new HashMap<>(ind);
		final PlacementTopology.StreamPlacement s = streams.get(rng.nextInt(streams.size()));
		next.put(s.streamKey(), dcs.get(rng.nextInt(dcs.size())));
		return next;
	}

	private Map<String, String> migrateFromElite(
			Map<String, String> ind,
			Map<String, String> elite,
			List<PlacementTopology.StreamPlacement> streams,
			Random rng
	) {
		final Map<String, String> next = new HashMap<>(ind);
		final PlacementTopology.StreamPlacement s = streams.get(rng.nextInt(streams.size()));
		next.put(s.streamKey(), elite.get(s.streamKey()));
		return next;
	}

	private Map<String, String> bestDcIndividual(
			List<Map<String, String>> pop,
			List<PlacementTopology.StreamPlacement> streams,
			PlacementScore sensors,
			PlacementTopology topology
	) {
		Map<String, String> best = pop.getFirst();
		double bestFit = Double.POSITIVE_INFINITY;
		for (Map<String, String> ind : pop) {
			final double fit = dcFitness(ind, streams, sensors, topology);
			if (fit < bestFit) {
				bestFit = fit;
				best = ind;
			}
		}
		return new HashMap<>(best);
	}

	private double dcFitness(
			Map<String, String> dcAssign,
			List<PlacementTopology.StreamPlacement> streams,
			PlacementScore sensors,
			PlacementTopology topology
	) {
		final String localDc = topology.localDc() == null ? "dc-local" : topology.localDc();
		double cost = 0.0;
		final Map<String, Integer> counts = new HashMap<>();
		for (PlacementTopology.StreamPlacement s : streams) {
			final String dc = dcAssign.getOrDefault(s.streamKey(), localDc);
			counts.merge(dc, 1, Integer::sum);
			final boolean remote = !Objects.equals(dc, localDc);
			if (remote) {
				cost += 0.35 * normalize(sensors.rttMs(), 200.0);
				cost += 0.20 * normalize(s.applyLag(), 1000.0);
			} else if (sensors.heapPressure() > 0.75 || sensors.queueDepth() > 5_000) {
				cost += 0.25 * sensors.heapPressure();
			}
		}
		cost += 0.15 * imbalance(counts.values().stream().mapToInt(Integer::intValue).toArray());
		cost += diversityPenaltyDc(dcAssign, streams);
		return cost;
	}

	private List<Map<String, String>> initNodePopulation(
			List<PlacementTopology.StreamPlacement> streams,
			Map<String, String> dcAssign,
			Map<String, List<String>> peersByDc,
			PlacementTopology topology,
			Random rng
	) {
		final List<Map<String, String>> pop = new ArrayList<>(nodePopulationSize);
		pop.add(currentNodeAssignment(streams, topology));
		while (pop.size() < nodePopulationSize) {
			final Map<String, String> ind = new HashMap<>();
			for (PlacementTopology.StreamPlacement s : streams) {
				ind.put(s.streamKey(), pickPeerForDc(dcAssign.get(s.streamKey()), peersByDc, topology, rng));
			}
			pop.add(ind);
		}
		return pop;
	}

	private Map<String, String> currentNodeAssignment(
			List<PlacementTopology.StreamPlacement> streams,
			PlacementTopology topology
	) {
		final Map<String, String> ind = new HashMap<>();
		for (PlacementTopology.StreamPlacement s : streams) {
			ind.put(s.streamKey(), effectiveOwner(s, topology.localNodeId()));
		}
		return ind;
	}

	private String pickPeerForDc(
			String dc,
			Map<String, List<String>> peersByDc,
			PlacementTopology topology,
			Random rng
	) {
		final String localDc = topology.localDc() == null ? "dc-local" : topology.localDc();
		final String targetDc = dc == null ? localDc : dc;
		final List<String> peers = peersByDc.get(targetDc);
		if (peers != null && !peers.isEmpty()) {
			return peers.get(rng.nextInt(peers.size()));
		}
		// Fall back: any peer, prefer local-DC peers when present.
		final List<PlacementTopology.PeerEndpoint> all = topology.peers();
		return all.get(rng.nextInt(all.size())).id();
	}

	private void evolveNodePopulation(
			List<Map<String, String>> pop,
			List<PlacementTopology.StreamPlacement> streams,
			Map<String, String> dcAssign,
			Map<String, List<String>> peersByDc,
			PlacementScore sensors,
			PlacementTopology topology,
			Random rng
	) {
		for (int gen = 0; gen < generationsPerTick; gen++) {
			final Map<String, String> elite = bestNodeIndividual(pop, streams, sensors, topology);
			for (int i = 0; i < pop.size(); i++) {
				Map<String, String> ind = pop.get(i);
				if (rng.nextDouble() < mutationRate) {
					ind = mutateNode(ind, streams, dcAssign, peersByDc, topology, rng);
				}
				if (rng.nextDouble() < diversityWeight) {
					ind = migrateFromElite(ind, elite, streams, rng);
				}
				pop.set(i, ind);
			}
			pop.set(0, elite);
		}
	}

	private Map<String, String> mutateNode(
			Map<String, String> ind,
			List<PlacementTopology.StreamPlacement> streams,
			Map<String, String> dcAssign,
			Map<String, List<String>> peersByDc,
			PlacementTopology topology,
			Random rng
	) {
		final Map<String, String> next = new HashMap<>(ind);
		final PlacementTopology.StreamPlacement s = streams.get(rng.nextInt(streams.size()));
		next.put(s.streamKey(), pickPeerForDc(dcAssign.get(s.streamKey()), peersByDc, topology, rng));
		return next;
	}

	private Map<String, String> bestNodeIndividual(
			List<Map<String, String>> pop,
			List<PlacementTopology.StreamPlacement> streams,
			PlacementScore sensors,
			PlacementTopology topology
	) {
		Map<String, String> best = pop.getFirst();
		double bestFit = Double.POSITIVE_INFINITY;
		for (Map<String, String> ind : pop) {
			final double fit = nodeFitness(ind, streams, sensors, topology);
			if (fit < bestFit) {
				bestFit = fit;
				best = ind;
			}
		}
		return new HashMap<>(best);
	}

	double nodeFitness(
			Map<String, String> nodeAssign,
			List<PlacementTopology.StreamPlacement> streams,
			PlacementScore sensors,
			PlacementTopology topology
	) {
		final String localDc = topology.localDc() == null ? "dc-local" : topology.localDc();
		final Map<String, String> peerDc = new HashMap<>();
		for (PlacementTopology.PeerEndpoint p : topology.peers()) {
			peerDc.put(p.id(), p.dc() == null ? localDc : p.dc());
		}
		double cost = 0.0;
		final Map<String, Integer> load = new HashMap<>();
		for (PlacementTopology.StreamPlacement s : streams) {
			final String target = nodeAssign.getOrDefault(s.streamKey(), topology.localNodeId());
			load.merge(target, 1, Integer::sum);
			final String pin = s.affinityPin();
			if (pin != null && !pin.equals(target)) {
				cost += 2.0; // hard penalty: never prefer breaking pins
			}
			final boolean localOwner = target.equals(topology.localNodeId());
			final String targetDc = localOwner ? localDc : peerDc.getOrDefault(target, localDc);
			final boolean remoteDc = !Objects.equals(targetDc, localDc);

			cost += 0.30 * normalize(s.applyLag(), 1000.0);
			if (remoteDc) {
				cost += 0.25 * normalize(sensors.rttMs(), 200.0);
			}
			if (localOwner) {
				cost += 0.20 * sensors.heapPressure();
				cost += 0.15 * normalize(sensors.queueDepth(), 10_000.0);
			} else {
				cost += 0.10 * (1.0 - sensors.hitRate());
			}
		}
		cost += 0.20 * imbalance(load.values().stream().mapToInt(Integer::intValue).toArray());
		cost += diversityPenaltyNode(nodeAssign, streams);
		return cost;
	}

	private List<ShardMigrateAction> toMigrations(
			List<PlacementTopology.StreamPlacement> streams,
			Map<String, String> eliteNode,
			PlacementTopology topology
	) {
		final List<ScoredMove> moves = new ArrayList<>();
		for (PlacementTopology.StreamPlacement s : streams) {
			final String target = eliteNode.get(s.streamKey());
			if (target == null) {
				continue;
			}
			final String pin = s.affinityPin();
			if (pin != null) {
				if (pin.equals(topology.localNodeId())) {
					continue;
				}
				if (!pin.equals(target)) {
					// Prefer pin over optimizer suggestion.
					if (!pin.equals(effectiveOwner(s, topology.localNodeId()))) {
						moves.add(new ScoredMove(
								new ShardMigrateAction(s.domainType(), s.shard(), pin),
								s.applyLag()
						));
					}
					continue;
				}
			}
			final String current = effectiveOwner(s, topology.localNodeId());
			if (!target.equals(current) && !target.equals(topology.localNodeId())) {
				moves.add(new ScoredMove(
						new ShardMigrateAction(s.domainType(), s.shard(), target),
						s.applyLag()
				));
			}
		}
		moves.sort((a, b) -> Double.compare(b.lag(), a.lag()));
		final List<ShardMigrateAction> out = new ArrayList<>();
		for (ScoredMove m : moves) {
			if (out.size() >= maxMigratesPerTick) {
				break;
			}
			out.add(m.action());
		}
		return out;
	}

	private PlacementHint hintFromSensors(PlacementScore sensors) {
		if (sensors == null) {
			return PlacementHint.KEEP;
		}
		final double composite = sensors.composite();
		if (composite >= migrateThreshold + 0.2) {
			return PlacementHint.SHED_LOAD;
		}
		if (composite <= migrateThreshold * 0.5 && sensors.hitRate() > 0.7) {
			return PlacementHint.ATTRACT_LEARNER;
		}
		if (sensors.rttMs() > 100) {
			return PlacementHint.PREFER_DC;
		}
		return PlacementHint.KEEP;
	}

	private PlacementHint hintFromPlan(
			PlacementScore sensors,
			List<ShardMigrateAction> migrations,
			PlacementTopology topology
	) {
		if (migrations.isEmpty()) {
			return hintFromSensors(sensors);
		}
		final String localDc = topology.localDc() == null ? "dc-local" : topology.localDc();
		final Map<String, String> peerDc = new HashMap<>();
		for (PlacementTopology.PeerEndpoint p : topology.peers()) {
			peerDc.put(p.id(), p.dc() == null ? localDc : p.dc());
		}
		boolean anyRemote = false;
		boolean anyLocal = false;
		for (ShardMigrateAction m : migrations) {
			final String dc = peerDc.getOrDefault(m.targetPeerId(), localDc);
			if (Objects.equals(dc, localDc)) {
				anyLocal = true;
			} else {
				anyRemote = true;
			}
		}
		if (sensors.heapPressure() > 0.8 || sensors.composite() >= migrateThreshold + 0.2) {
			return PlacementHint.SHED_LOAD;
		}
		if (anyRemote && sensors.rttMs() > 100) {
			return PlacementHint.PREFER_DC;
		}
		if (anyLocal && sensors.hitRate() > 0.6) {
			return PlacementHint.ATTRACT_LEARNER;
		}
		return anyRemote ? PlacementHint.PREFER_DC : PlacementHint.SHED_LOAD;
	}

	private double diversityPenaltyDc(Map<String, String> assign, List<PlacementTopology.StreamPlacement> streams) {
		if (streams.size() < 2) {
			return 0.0;
		}
		int same = 0;
		final String first = assign.get(streams.getFirst().streamKey());
		for (PlacementTopology.StreamPlacement s : streams) {
			if (Objects.equals(first, assign.get(s.streamKey()))) {
				same++;
			}
		}
		return diversityWeight * ((double) same / (double) streams.size());
	}

	private double diversityPenaltyNode(Map<String, String> assign, List<PlacementTopology.StreamPlacement> streams) {
		if (streams.size() < 2) {
			return 0.0;
		}
		int same = 0;
		final String first = assign.get(streams.getFirst().streamKey());
		for (PlacementTopology.StreamPlacement s : streams) {
			if (Objects.equals(first, assign.get(s.streamKey()))) {
				same++;
			}
		}
		return diversityWeight * ((double) same / (double) streams.size());
	}

	private static String effectiveOwner(PlacementTopology.StreamPlacement s, String localNodeId) {
		if (s.affinityPin() != null) {
			return s.affinityPin();
		}
		return s.currentOwner() == null ? localNodeId : s.currentOwner();
	}

	private static double normalize(double v, double max) {
		if (max <= 0) {
			return 0;
		}
		return Math.min(1.0, Math.max(0.0, v / max));
	}

	private static double imbalance(int[] loads) {
		if (loads == null || loads.length <= 1) {
			return 0.0;
		}
		double sum = 0;
		for (int l : loads) {
			sum += l;
		}
		final double mean = sum / loads.length;
		double var = 0;
		for (int l : loads) {
			final double d = l - mean;
			var += d * d;
		}
		final double std = Math.sqrt(var / loads.length);
		return mean <= 0 ? 0.0 : Math.min(1.0, std / mean);
	}

	private record ScoredMove(ShardMigrateAction action, double lag) {
	}
}
