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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code apply-voter-set-hints=true} invokes {@code OrchidNode.applyRemoteVoters} path.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class ApplyVoterSetHintsIT {

	private ReplicationCoordinator coordinator;

	@AfterEach
	void tearDown() {
		if (coordinator != null) {
			coordinator.stop();
		}
	}

	@Test
	void applyVoterSetHintsEnabledRewritesRemoteVoters() throws Exception {
		final GridConfigurationProperties props = ReplTestSupport.props(
				"hint-a",
				"hint-cluster",
				"dc-a",
				0,
				Files.createTempDirectory("apply-voter-hints"),
				List.of(ReplTestSupport.peer("hint-b", "dc-b", 1))
		);
		props.getReplication().getCrossDc().setMode("SYNC_VOTERS_ACROSS_DC");
		props.getReplication().getCrossDc().getVoters().add("hint-b");
		props.getReplication().getPlacementOptimizer().setApplyVoterSetHints(true);

		coordinator = new ReplicationCoordinator(props);
		coordinator.start();

		assertEquals(Set.of("hint-b"), coordinator.getOrchidNode().multiDcConfig().remoteVoterIds());

		coordinator.applyVoterSetHintIfEnabled(Set.of("hint-c", "hint-d"));

		assertEquals(Set.of("hint-c", "hint-d"), coordinator.getOrchidNode().multiDcConfig().remoteVoterIds());
		assertTrue(!coordinator.getOrchidNode().multiDcConfig().phaseCoupling());
	}

	@Test
	void applyVoterSetHintsDisabledLeavesRemoteVoters() throws Exception {
		final GridConfigurationProperties props = ReplTestSupport.props(
				"hint-off-a",
				"hint-off-cluster",
				"dc-a",
				0,
				Files.createTempDirectory("apply-voter-hints-off"),
				List.of(ReplTestSupport.peer("hint-off-b", "dc-b", 1))
		);
		props.getReplication().getCrossDc().setMode("SYNC_VOTERS_ACROSS_DC");
		props.getReplication().getCrossDc().getVoters().add("hint-off-b");
		props.getReplication().getPlacementOptimizer().setApplyVoterSetHints(false);

		coordinator = new ReplicationCoordinator(props);
		coordinator.start();

		coordinator.applyVoterSetHintIfEnabled(Set.of("hint-off-c"));
		assertEquals(Set.of("hint-off-b"), coordinator.getOrchidNode().multiDcConfig().remoteVoterIds());
	}
}