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
package org.genfork.grid.context.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.genfork.grid.context.config.health.GridLivenessHealthIndicator;
import org.genfork.grid.context.config.health.GridReadinessHealthIndicator;
import org.genfork.grid.context.config.metrics.GridMetricsBinder;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlServerRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Actuator readiness/liveness + Micrometer Prometheus binders for grid metrics.
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(GridConfigurationProperties.class)
@ConditionalOnClass({MeterRegistry.class, HealthIndicator.class})
public class GridObservabilityAutoConfiguration {

	public static final String READINESS_BEAN_NAME = "gridReadiness";
	public static final String LIVENESS_BEAN_NAME = "gridLiveness";

	@Bean
	@ConditionalOnMissingBean(GridMetricsBinder.class)
	public MeterBinder gridMetricsBinder(ObjectProvider<ReplicationCoordinator> coordinatorProvider) {
		return new GridMetricsBinder(coordinatorProvider);
	}

	@Bean(name = READINESS_BEAN_NAME)
	@ConditionalOnMissingBean(name = READINESS_BEAN_NAME)
	public HealthIndicator gridReadiness(
			GridConfigurationProperties properties,
			ObjectProvider<SqlServerRuntime> sqlRuntimeProvider,
			ObjectProvider<ReplicationCoordinator> coordinatorProvider
	) {
		return new GridReadinessHealthIndicator(properties, sqlRuntimeProvider, coordinatorProvider);
	}

	@Bean(name = LIVENESS_BEAN_NAME)
	@ConditionalOnMissingBean(name = LIVENESS_BEAN_NAME)
	public HealthIndicator gridLiveness(ObjectProvider<ReplicationCoordinator> coordinatorProvider) {
		return new GridLivenessHealthIndicator(coordinatorProvider);
	}
}
