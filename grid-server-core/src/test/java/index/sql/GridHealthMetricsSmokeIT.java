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
package index.sql;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.genfork.grid.context.config.GridAutoConfiguration;
import org.genfork.grid.context.config.GridObservabilityAutoConfiguration;
import org.genfork.grid.context.config.GridSqlAutoConfiguration;
import org.genfork.grid.context.config.health.GridReadinessHealthIndicator;
import org.genfork.grid.context.config.metrics.GridMetricsBinder;
import org.genfork.grid.metrics.SqlTxMetrics;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke: readiness health + Micrometer binder registration.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
@SpringBootTest(classes = {
		GridAutoConfiguration.class,
		GridSqlAutoConfiguration.class,
		GridObservabilityAutoConfiguration.class,
		GridHealthMetricsSmokeIT.Meters.class
})
public class GridHealthMetricsSmokeIT {
	private static final int PORT = 25443;

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("grid.durability.enabled", () -> "false");
		registry.add("grid.sql.data-dir", () -> tempDir.resolve("catalog").toString());
		registry.add("grid.sql.default-shards", () -> "4");
		registry.add("grid.sql-server.enabled", () -> "true");
		registry.add("grid.sql-server.host", () -> "127.0.0.1");
		registry.add("grid.sql-server.port", () -> Integer.toString(PORT));
		registry.add("grid.sql-server.user", () -> "u");
		registry.add("grid.sql-server.password", () -> "p");
	}

	@TestConfiguration
	static class Meters {
		@Bean
		MeterRegistry meterRegistry(ObjectProvider<MeterBinder> binders) {
			final SimpleMeterRegistry registry = new SimpleMeterRegistry();
			binders.orderedStream().forEach(binder -> binder.bindTo(registry));
			return registry;
		}
	}

	@Autowired
	private SqlServer sqlServer;

	@Autowired
	private SqlEngine engine;

	@Autowired
	@Qualifier(GridObservabilityAutoConfiguration.READINESS_BEAN_NAME)
	private HealthIndicator gridReadiness;

	@Autowired
	private MeterRegistry meterRegistry;

	@Test
	void readinessUpWhenSqlTcpListening() {
		assertTrue(sqlServer.isListening());
		final Health health = gridReadiness.health();
		assertEquals(Status.UP, health.getStatus());
		assertEquals("listening", health.getDetails().get(GridReadinessHealthIndicator.DETAIL_SQL_TCP));
		assertNotNull(health.getDetails().get(GridReadinessHealthIndicator.DETAIL_LOCK_WAIT_TIMEOUTS));
		assertNotNull(health.getDetails().get(GridReadinessHealthIndicator.DETAIL_LOCK_CANCELS));
		assertNotNull(health.getDetails().get(GridReadinessHealthIndicator.DETAIL_SQL_CANCEL_INFLIGHT));
	}

	@Test
	void binderExposesSqlCounters() {
		final long before = SqlTxMetrics.executions();
		engine.execute("CREATE TABLE hm (id INT PRIMARY KEY)");
		assertTrue(SqlTxMetrics.executions() > before);
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_SQL_EXECUTIONS).functionCounter());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_SQL_FAN_IN_CALLS).functionCounter());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_SQL_CANCEL_REQUESTS).functionCounter());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_SQL_LOCK_WAIT_TIMEOUTS).functionCounter());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_OPLOG_PUSH_SENT).functionCounter());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_SEALED_MISS).functionCounter());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_ORCHID_R).gauge());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_REPAIR_ISSUED).gauge());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_REPAIR_APPLIED).gauge());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_RPO_ESTIMATE_MS).gauge());
		assertNotNull(meterRegistry.find(GridMetricsBinder.METRIC_SWARM_HINT).gauge());
	}
}