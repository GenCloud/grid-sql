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

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Match when local durability or peer replication is enabled.
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
public final class DurabilityOrReplicationCondition extends AnyNestedCondition {
	public DurabilityOrReplicationCondition() {
		super(ConfigurationPhase.PARSE_CONFIGURATION);
	}

	@ConditionalOnProperty(prefix = "grid.durability", name = "enabled", havingValue = "true")
	static final class DurabilityEnabled {
	}

	@ConditionalOnProperty(prefix = "grid.replication", name = "enabled", havingValue = "true")
	static final class ReplicationEnabled {
	}
}