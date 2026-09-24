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

import org.genfork.grid.overlay.OverlayStore;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Bootstraps {@link ReplicationCoordinator} when local durability and/or peer replication is on.
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(GridConfigurationProperties.class)
@Conditional(DurabilityOrReplicationCondition.class)
public class GridReplicationAutoConfiguration {

	@Bean(initMethod = "start", destroyMethod = "stop")
	public ReplicationCoordinator replicationCoordinator(
			GridConfigurationProperties properties,
			ObjectProvider<OverlayStore> overlayStore
	) {
		final ReplicationCoordinator coordinator = new ReplicationCoordinator(properties);
		overlayStore.ifAvailable(coordinator::setOverlayStore);
		return coordinator;
	}
}