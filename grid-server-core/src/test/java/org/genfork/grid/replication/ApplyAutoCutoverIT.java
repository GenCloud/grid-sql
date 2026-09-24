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
package org.genfork.grid.replication;

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code apply-auto-cutover} gate on {@link ReplicationCoordinator} (default on).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class ApplyAutoCutoverIT {

	private ReplicationCoordinator coordinator;

	@AfterEach
	void tearDown() {
		if (coordinator != null) {
			coordinator.stop();
		}
	}

	@Test
	void applyAutoCutoverDefaultsTrue() throws Exception {
		final GridConfigurationProperties props = ReplTestSupport.props(
				"cut-a",
				"cut-cluster",
				"dc-a",
				0,
				Files.createTempDirectory("apply-auto-cutover-on"),
				List.of(ReplTestSupport.peer("cut-b", "dc-b", 1))
		);

		coordinator = new ReplicationCoordinator(props);
		coordinator.start();
		assertTrue(coordinator.isApplyAutoCutover());
	}

	@Test
	void applyAutoCutoverOptsOutWhenDisabled() throws Exception {
		final GridConfigurationProperties props = ReplTestSupport.props(
				"cut-off-a",
				"cut-off-cluster",
				"dc-a",
				0,
				Files.createTempDirectory("apply-auto-cutover-off"),
				List.of(ReplTestSupport.peer("cut-off-b", "dc-b", 1))
		);
		props.getReplication().getSwarm().setApplyAutoCutover(false);

		coordinator = new ReplicationCoordinator(props);
		coordinator.start();
		assertFalse(coordinator.isApplyAutoCutover());
	}
}
