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
package org.genfork.grid.context.config.health;

import java.lang.management.ManagementFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.swarm.AdaptiveReplicaSwarm;
import org.genfork.grid.replication.swarm.PlacementHint;
import org.genfork.grid.threading.ThreadService;

/**
 * Thin liveness: process alive + logic executor present. Does not gate ORCHID / write admission.
 * <p>
 * When replication is enabled, details include orchidR / repair / RPO / swarm hint for ops
 * diagnosis without changing UP/DOWN semantics.
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
public final class GridLivenessHealthIndicator implements HealthIndicator {
	public static final String DETAIL_LOGIC_EXECUTOR = "logicExecutor";
	public static final String DETAIL_UPTIME_MS = "uptimeMs";
	public static final String DETAIL_PID = "pid";
	public static final String DETAIL_ORCHID_R = "orchidR";
	public static final String DETAIL_REPAIR_ISSUED = "repairIssued";
	public static final String DETAIL_REPAIR_APPLIED = "repairApplied";
	public static final String DETAIL_RPO_ESTIMATE_MS = "rpoEstimateMs";
	public static final String DETAIL_SWARM_HINT = "swarmHint";

	private static final String STATUS_UP = "up";
	private static final String STATUS_DOWN = "down";
	private static final String STATUS_N_A = "n/a";

	private final ObjectProvider<ReplicationCoordinator> coordinatorProvider;

	public GridLivenessHealthIndicator(ObjectProvider<ReplicationCoordinator> coordinatorProvider) {
		this.coordinatorProvider = coordinatorProvider;
	}

	@Override
	public Health health() {
		final boolean logicOk = ThreadService.getLogicExecutor() != null;
		final long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
		final long pid = ProcessHandle.current().pid();
		final Health.Builder builder = logicOk ? Health.up() : Health.down();
		builder
				.withDetail(DETAIL_LOGIC_EXECUTOR, logicOk ? STATUS_UP : STATUS_DOWN)
				.withDetail(DETAIL_UPTIME_MS, uptimeMs)
				.withDetail(DETAIL_PID, pid);
		withReplicationDetails(builder);
		return builder.build();
	}

	private void withReplicationDetails(Health.Builder builder) {
		final ReplicationCoordinator coordinator =
				coordinatorProvider == null ? null : coordinatorProvider.getIfAvailable();
		if (coordinator == null || !coordinator.isEnabled()) {
			builder.withDetail(DETAIL_ORCHID_R, STATUS_N_A);
			builder.withDetail(DETAIL_REPAIR_ISSUED, STATUS_N_A);
			builder.withDetail(DETAIL_REPAIR_APPLIED, STATUS_N_A);
			builder.withDetail(DETAIL_RPO_ESTIMATE_MS, STATUS_N_A);
			builder.withDetail(DETAIL_SWARM_HINT, STATUS_N_A);
			return;
		}
		final ReplicationNodeState state = coordinator.getNodeState();
		builder.withDetail(DETAIL_ORCHID_R, state == null ? 0.0d : state.orchidR());
		final HomologousRepair repair = coordinator.getHomologousRepair();
		builder.withDetail(DETAIL_REPAIR_ISSUED, repair == null ? 0L : repair.repairIssued());
		builder.withDetail(DETAIL_REPAIR_APPLIED, repair == null ? 0L : repair.repairApplied());
		final CrossDcPublisher publisher = coordinator.getCrossDcPublisher();
		final long rpo = publisher == null || publisher.getMetrics() == null
				? 0L
				: publisher.getMetrics().rpoEstimateMs();
		builder.withDetail(DETAIL_RPO_ESTIMATE_MS, rpo);
		final AdaptiveReplicaSwarm swarm = coordinator.getSwarm();
		final PlacementHint hint = swarm == null || swarm.getLastHint() == null
				? null
				: swarm.getLastHint().get();
		builder.withDetail(DETAIL_SWARM_HINT, hint == null ? STATUS_N_A : hint.name());
	}
}
