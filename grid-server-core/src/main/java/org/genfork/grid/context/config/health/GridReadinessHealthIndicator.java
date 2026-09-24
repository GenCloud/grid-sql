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

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.metrics.SqlLockMetrics;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.swarm.AdaptiveReplicaSwarm;
import org.genfork.grid.replication.swarm.PlacementHint;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.sql.netty.SqlServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness: SQL TCP bound (when enabled) and solo durable OK or ORCHID synced.
 * <p>
 * Canon ops surface is Actuator {@code /health/readiness} (with Jepsen
 * {@code management.endpoints.web.base-path: /}) — not a rich {@code /replication/status}.
 * Details include lock-wait / cancel counters plus orchidR / repair / RPO / swarm hint;
 * UP/DOWN admission criteria are unchanged (fail-closed when orchid not synced).
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
public final class GridReadinessHealthIndicator implements HealthIndicator {
	public static final String DETAIL_SQL_TCP = "sqlTcp";
	public static final String DETAIL_DURABLE = "durableOrSynced";
	public static final String DETAIL_ORCHID_SYNCED = "orchidSynced";
	public static final String DETAIL_REASON = "reason";
	public static final String DETAIL_LOCK_WAIT_TIMEOUTS = "lockWaitTimeouts";
	public static final String DETAIL_LOCK_CANCELS = "lockCancels";
	public static final String DETAIL_SQL_CANCEL_INFLIGHT = "sqlCancelInflight";
	public static final String DETAIL_APPLY_LAG_STALE = "applyLagStale";
	public static final String DETAIL_WRITER_ELIGIBLE = "writerEligible";
	public static final String DETAIL_MAX_TX_CONTEXTS = "maxTxContexts";
	public static final String DETAIL_ORCHID_R = "orchidR";
	public static final String DETAIL_REPAIR_ISSUED = "repairIssued";
	public static final String DETAIL_REPAIR_APPLIED = "repairApplied";
	public static final String DETAIL_RPO_ESTIMATE_MS = "rpoEstimateMs";
	public static final String DETAIL_SWARM_HINT = "swarmHint";

	private static final String REASON_SQL_TCP_DOWN = "sql_tcp_down";
	private static final String REASON_ORCHID_NOT_SYNCED = "orchid_not_synced";
	private static final String STATUS_LISTENING = "listening";
	private static final String STATUS_DISABLED = "disabled";
	private static final String STATUS_UP = "up";
	private static final String STATUS_DOWN = "down";
	private static final String STATUS_N_A = "n/a";
	private static final double ORCHID_R_ABSENT = 0.0d;

	private final GridConfigurationProperties properties;
	private final ObjectProvider<SqlServerRuntime> sqlRuntimeProvider;
	private final ObjectProvider<ReplicationCoordinator> coordinatorProvider;

	public GridReadinessHealthIndicator(
			GridConfigurationProperties properties,
			ObjectProvider<SqlServerRuntime> sqlRuntimeProvider,
			ObjectProvider<ReplicationCoordinator> coordinatorProvider
	) {
		this.properties = properties;
		this.sqlRuntimeProvider = sqlRuntimeProvider;
		this.coordinatorProvider = coordinatorProvider;
	}

	@Override
	public Health health() {
		final boolean tcpEnabled = properties.getSqlServer() != null && properties.getSqlServer().isEnabled();
		if (tcpEnabled) {
			final SqlServerRuntime runtime = sqlRuntimeProvider.getIfAvailable();
			final SqlServer tcp = runtime == null ? null : runtime.tcpServer();
			if (tcp == null || !tcp.isListening()) {
				return Health.down()
						.withDetail(DETAIL_SQL_TCP, STATUS_DOWN)
						.withDetail(DETAIL_REASON, REASON_SQL_TCP_DOWN)
						.build();
			}
		}

		final ReplicationCoordinator coordinator = coordinatorProvider.getIfAvailable();
		final boolean durablePath = coordinator != null && coordinator.isEnabled();
		if (!durablePath) {
			return withOpsDetails(Health.up()
					.withDetail(DETAIL_SQL_TCP, tcpEnabled ? STATUS_LISTENING : STATUS_DISABLED)
					.withDetail(DETAIL_DURABLE, STATUS_N_A)
					.withDetail(DETAIL_ORCHID_SYNCED, STATUS_N_A)
					.withDetail(DETAIL_APPLY_LAG_STALE, STATUS_N_A)
					.withDetail(DETAIL_WRITER_ELIGIBLE, STATUS_N_A))
					.build();
		}

		final boolean synced = coordinator.getNodeState() != null && coordinator.getNodeState().isSynced();
		if (!synced) {
			return withOpsDetails(Health.down()
					.withDetail(DETAIL_SQL_TCP, tcpEnabled ? STATUS_LISTENING : STATUS_DISABLED)
					.withDetail(DETAIL_DURABLE, STATUS_UP)
					.withDetail(DETAIL_ORCHID_SYNCED, Boolean.FALSE)
					.withDetail(DETAIL_APPLY_LAG_STALE, coordinator.isApplyLagStale())
					.withDetail(DETAIL_WRITER_ELIGIBLE, coordinator.isWriterEligible())
					.withDetail(DETAIL_REASON, REASON_ORCHID_NOT_SYNCED))
					.build();
		}

		return withOpsDetails(Health.up()
				.withDetail(DETAIL_SQL_TCP, tcpEnabled ? STATUS_LISTENING : STATUS_DISABLED)
				.withDetail(DETAIL_DURABLE, STATUS_UP)
				.withDetail(DETAIL_ORCHID_SYNCED, Boolean.TRUE)
				.withDetail(DETAIL_APPLY_LAG_STALE, coordinator.isApplyLagStale())
				.withDetail(DETAIL_WRITER_ELIGIBLE, coordinator.isWriterEligible()))
				.build();
	}

	private Health.Builder withOpsDetails(Health.Builder builder) {
		builder.withDetail(DETAIL_LOCK_WAIT_TIMEOUTS, SqlLockMetrics.waitTimeouts());
		builder.withDetail(DETAIL_LOCK_CANCELS, SqlLockMetrics.waitCancels());
		builder.withDetail(DETAIL_SQL_CANCEL_INFLIGHT, DistributedQueryMetrics.cancelActive());
		final Integer maxTx = properties.getSql() == null ? null : properties.getSql().getMaxTxContexts();
		if (maxTx != null) {
			builder.withDetail(DETAIL_MAX_TX_CONTEXTS, maxTx);
		}
		withReplicationDetails(builder);
		return builder;
	}

	private void withReplicationDetails(Health.Builder builder) {
		final ReplicationCoordinator coordinator = coordinatorProvider.getIfAvailable();
		if (coordinator == null || !coordinator.isEnabled()) {
			builder.withDetail(DETAIL_ORCHID_R, STATUS_N_A);
			builder.withDetail(DETAIL_REPAIR_ISSUED, STATUS_N_A);
			builder.withDetail(DETAIL_REPAIR_APPLIED, STATUS_N_A);
			builder.withDetail(DETAIL_RPO_ESTIMATE_MS, STATUS_N_A);
			builder.withDetail(DETAIL_SWARM_HINT, STATUS_N_A);
			return;
		}
		final ReplicationNodeState state = coordinator.getNodeState();
		builder.withDetail(DETAIL_ORCHID_R, state == null ? ORCHID_R_ABSENT : state.orchidR());
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
