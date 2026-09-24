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
package org.genfork.grid.context.config.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.metrics.SqlLockMetrics;
import org.genfork.grid.metrics.SqlTxMetrics;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.snapshot.sealed.SealedMetrics;
import org.genfork.grid.replication.swarm.AdaptiveReplicaSwarm;
import org.genfork.grid.replication.swarm.PlacementHint;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Micrometer binder over {@link ReplicationMetrics}, {@link SealedMetrics}, {@link SqlTxMetrics},
 * and live coordinator signals (ORCHID R, repair, RPO, swarm hint).
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
public final class GridMetricsBinder implements MeterBinder {
	public static final String METRIC_OPLOG_PUSH_SENT = "grid.replication.oplog_push_sent";
	public static final String METRIC_OPLOG_PUSH_RECV = "grid.replication.oplog_push_recv";
	public static final String METRIC_APPLY_ACK_SENT = "grid.replication.apply_ack_sent";
	public static final String METRIC_APPLY_ACK_RECV = "grid.replication.apply_ack_recv";
	public static final String METRIC_ORCHID_RPC_SENT = "grid.replication.orchid_rpc_sent";
	public static final String METRIC_CONNECT_FAILURES = "grid.replication.connect_failures";
	public static final String METRIC_SHIP_BACKPRESSURE = "grid.replication.ship_backpressure";
	public static final String METRIC_SEALED_MISSES = "grid.replication.sealed_misses";
	public static final String METRIC_OVERLAY_PINNED = "grid.replication.overlay_pinned_keys";
	public static final String METRIC_ORCHID_WAIT_P99_NS = "grid.replication.orchid_wait_p99_ns";
	public static final String METRIC_OPLOG_FSYNC_P99_NS = "grid.replication.oplog_fsync_p99_ns";
	public static final String METRIC_MAP_HIT_RATE = "grid.replication.map_hit_rate";

	public static final String METRIC_ORCHID_R = "grid.replication.orchid_r";
	public static final String METRIC_REPAIR_ISSUED = "grid.replication.repair_issued";
	public static final String METRIC_REPAIR_APPLIED = "grid.replication.repair_applied";
	public static final String METRIC_RPO_ESTIMATE_MS = "grid.replication.rpo_estimate_ms";
	public static final String METRIC_SWARM_HINT = "grid.replication.swarm_hint";

	public static final String METRIC_SEALED_MISS = "grid.sealed.miss";
	public static final String METRIC_SEALED_WINDOW_REMAP = "grid.sealed.window_remap";
	public static final String METRIC_SEALED_STICKY_REJECTED = "grid.sealed.sticky_rejected";
	public static final String METRIC_SEALED_INDEX_HIT = "grid.sealed.index_hit";
	public static final String METRIC_SEALED_INDEX_MISS = "grid.sealed.index_miss";

	public static final String METRIC_SQL_EXECUTIONS = "grid.sql.executions";
	public static final String METRIC_SQL_COMMITS = "grid.sql.tx_commits";
	public static final String METRIC_SQL_ROLLBACKS = "grid.sql.tx_rollbacks";
	public static final String METRIC_SQL_SESSION_OPENS = "grid.sql.session_opens";
	public static final String METRIC_SQL_FAN_IN_CALLS = "grid.sql.distributed.fan_in_calls";
	public static final String METRIC_SQL_FAN_IN_SOURCES = "grid.sql.distributed.fan_in_sources";
	public static final String METRIC_SQL_FAN_IN_ROWS = "grid.sql.distributed.fan_in_rows";
	public static final String METRIC_SQL_CANCEL_REQUESTS = "grid.sql.cancel.requests";
	public static final String METRIC_SQL_CANCEL_ACTIVE = "grid.sql.cancel.active";
	public static final String METRIC_SQL_LOCK_WAIT_ACQUIRES = "grid.sql.lock.wait_acquires";
	public static final String METRIC_SQL_LOCK_WAIT_TIMEOUTS = "grid.sql.lock.wait_timeouts";
	public static final String METRIC_SQL_LOCK_WAIT_CANCELS = "grid.sql.lock.wait_cancels";
	public static final String METRIC_SQL_LOCK_WAIT_NANOS = "grid.sql.lock.wait_nanos";

	private static final double SWARM_HINT_ABSENT = -1.0d;
	private static final double ZERO = 0.0d;

	private final ObjectProvider<ReplicationCoordinator> coordinatorProvider;

	public GridMetricsBinder(ObjectProvider<ReplicationCoordinator> coordinatorProvider) {
		this.coordinatorProvider = coordinatorProvider;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		FunctionCounter.builder(METRIC_OPLOG_PUSH_SENT, ReplicationMetrics.class, m -> ReplicationMetrics.oplogPushSent())
				.description("OpLog push frames sent")
				.register(registry);
		FunctionCounter.builder(METRIC_OPLOG_PUSH_RECV, ReplicationMetrics.class, m -> ReplicationMetrics.oplogPushRecv())
				.description("OpLog push frames received")
				.register(registry);
		FunctionCounter.builder(METRIC_APPLY_ACK_SENT, ReplicationMetrics.class, m -> ReplicationMetrics.applyAckSent())
				.register(registry);
		FunctionCounter.builder(METRIC_APPLY_ACK_RECV, ReplicationMetrics.class, m -> ReplicationMetrics.applyAckRecv())
				.register(registry);
		FunctionCounter.builder(METRIC_ORCHID_RPC_SENT, ReplicationMetrics.class, m -> ReplicationMetrics.orchidRpcSent())
				.register(registry);
		FunctionCounter.builder(METRIC_CONNECT_FAILURES, ReplicationMetrics.class, m -> ReplicationMetrics.connectFailures())
				.register(registry);
		FunctionCounter.builder(METRIC_SHIP_BACKPRESSURE, ReplicationMetrics.class, m -> ReplicationMetrics.shipBackpressure())
				.register(registry);
		FunctionCounter.builder(METRIC_SEALED_MISSES, ReplicationMetrics.class, m -> ReplicationMetrics.sealedMisses())
				.register(registry);

		Gauge.builder(METRIC_OVERLAY_PINNED, ReplicationMetrics.class, m -> ReplicationMetrics.overlayPinnedKeys())
				.description("Live overlay pinned keys")
				.register(registry);
		Gauge.builder(METRIC_ORCHID_WAIT_P99_NS, ReplicationMetrics.class, m -> ReplicationMetrics.orchidWaitP99Ns())
				.register(registry);
		Gauge.builder(METRIC_OPLOG_FSYNC_P99_NS, ReplicationMetrics.class, m -> ReplicationMetrics.oplogFsyncP99Ns())
				.register(registry);
		Gauge.builder(METRIC_MAP_HIT_RATE, ReplicationMetrics.class, m -> ReplicationMetrics.mapHitRate())
				.register(registry);

		Gauge.builder(METRIC_ORCHID_R, this, GridMetricsBinder::sampleOrchidR)
				.description("ORCHID order parameter R")
				.register(registry);
		Gauge.builder(METRIC_REPAIR_ISSUED, this, GridMetricsBinder::sampleRepairIssued)
				.description("HomologousRepair issued count")
				.register(registry);
		Gauge.builder(METRIC_REPAIR_APPLIED, this, GridMetricsBinder::sampleRepairApplied)
				.description("HomologousRepair applied count")
				.register(registry);
		Gauge.builder(METRIC_RPO_ESTIMATE_MS, this, GridMetricsBinder::sampleRpoEstimateMs)
				.description("Cross-DC RPO estimate (lag ms)")
				.register(registry);
		Gauge.builder(METRIC_SWARM_HINT, this, GridMetricsBinder::sampleSwarmHintOrdinal)
				.description("AdaptiveReplicaSwarm last PlacementHint ordinal (-1 if absent)")
				.register(registry);

		FunctionCounter.builder(METRIC_SEALED_MISS, SealedMetrics.class, m -> SealedMetrics.SEALED_MISS.get())
				.register(registry);
		FunctionCounter.builder(METRIC_SEALED_WINDOW_REMAP, SealedMetrics.class, m -> SealedMetrics.SEALED_WINDOW_REMAP.get())
				.register(registry);
		FunctionCounter.builder(METRIC_SEALED_STICKY_REJECTED, SealedMetrics.class, m -> SealedMetrics.SEALED_STICKY_REJECTED.get())
				.register(registry);
		FunctionCounter.builder(METRIC_SEALED_INDEX_HIT, SealedMetrics.class, m -> SealedMetrics.SEALED_INDEX_HIT.get())
				.register(registry);
		FunctionCounter.builder(METRIC_SEALED_INDEX_MISS, SealedMetrics.class, m -> SealedMetrics.SEALED_INDEX_MISS.get())
				.register(registry);

		FunctionCounter.builder(METRIC_SQL_EXECUTIONS, SqlTxMetrics.class, m -> SqlTxMetrics.executions())
				.description("SQL execute dispatches")
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_COMMITS, SqlTxMetrics.class, m -> SqlTxMetrics.commits())
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_ROLLBACKS, SqlTxMetrics.class, m -> SqlTxMetrics.rollbacks())
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_SESSION_OPENS, SqlTxMetrics.class, m -> SqlTxMetrics.sessionOpens())
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_FAN_IN_CALLS, DistributedQueryMetrics.class,
						m -> DistributedQueryMetrics.fanInCalls())
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_FAN_IN_SOURCES, DistributedQueryMetrics.class,
						m -> DistributedQueryMetrics.fanInSources())
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_FAN_IN_ROWS, DistributedQueryMetrics.class,
						m -> DistributedQueryMetrics.fanInRows())
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_CANCEL_REQUESTS, DistributedQueryMetrics.class,
						m -> DistributedQueryMetrics.cancelRequests())
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_CANCEL_ACTIVE, DistributedQueryMetrics.class,
						m -> DistributedQueryMetrics.cancelActive())
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_LOCK_WAIT_ACQUIRES, SqlLockMetrics.class,
						m -> SqlLockMetrics.waitAcquires())
				.description("SQL record lock acquires (including waited)")
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_LOCK_WAIT_TIMEOUTS, SqlLockMetrics.class,
						m -> SqlLockMetrics.waitTimeouts())
				.description("SQL record lock wait timeouts")
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_LOCK_WAIT_CANCELS, SqlLockMetrics.class,
						m -> SqlLockMetrics.waitCancels())
				.description("SQL record lock waits aborted by CANCEL/interrupt")
				.register(registry);
		FunctionCounter.builder(METRIC_SQL_LOCK_WAIT_NANOS, SqlLockMetrics.class,
						m -> SqlLockMetrics.waitNanosTotal())
				.description("Total nanoseconds spent waiting for record locks")
				.register(registry);
	}

	private double sampleOrchidR() {
		final ReplicationCoordinator coordinator = coordinatorOrNull();
		if (coordinator == null || !coordinator.isEnabled()) {
			return ZERO;
		}
		final ReplicationNodeState state = coordinator.getNodeState();
		return state == null ? ZERO : state.orchidR();
	}

	private double sampleRepairIssued() {
		final HomologousRepair repair = homologousRepairOrNull();
		return repair == null ? ZERO : (double) repair.repairIssued();
	}

	private double sampleRepairApplied() {
		final HomologousRepair repair = homologousRepairOrNull();
		return repair == null ? ZERO : (double) repair.repairApplied();
	}

	private double sampleRpoEstimateMs() {
		final ReplicationCoordinator coordinator = coordinatorOrNull();
		if (coordinator == null || !coordinator.isEnabled()) {
			return ZERO;
		}
		final CrossDcPublisher publisher = coordinator.getCrossDcPublisher();
		if (publisher == null || publisher.getMetrics() == null) {
			return ZERO;
		}
		return (double) publisher.getMetrics().rpoEstimateMs();
	}

	private double sampleSwarmHintOrdinal() {
		final ReplicationCoordinator coordinator = coordinatorOrNull();
		if (coordinator == null || !coordinator.isEnabled()) {
			return SWARM_HINT_ABSENT;
		}
		final AdaptiveReplicaSwarm swarm = coordinator.getSwarm();
		if (swarm == null || swarm.getLastHint() == null) {
			return SWARM_HINT_ABSENT;
		}
		final PlacementHint hint = swarm.getLastHint().get();
		return hint == null ? SWARM_HINT_ABSENT : (double) hint.ordinal();
	}

	private HomologousRepair homologousRepairOrNull() {
		final ReplicationCoordinator coordinator = coordinatorOrNull();
		if (coordinator == null || !coordinator.isEnabled()) {
			return null;
		}
		return coordinator.getHomologousRepair();
	}

	private ReplicationCoordinator coordinatorOrNull() {
		if (coordinatorProvider == null) {
			return null;
		}
		return coordinatorProvider.getIfAvailable();
	}
}
