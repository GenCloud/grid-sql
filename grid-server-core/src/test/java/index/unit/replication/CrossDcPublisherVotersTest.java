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
package index.unit.replication;

import org.genfork.grid.context.config.GridConfigurationProperties.CrossDcProps;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static index.unit.replication.TxEnvelopeOpFixtures.upsert;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SYNC_VOTERS ships to voters (not learners-only); learners remain async targets.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class CrossDcPublisherVotersTest {

	@Test
	void resolveRemoteVotersExcludesLearners() {
		final CrossDcProps props = new CrossDcProps();
		props.setVoters(List.of());
		props.setLearners(List.of("learner-1"));
		final Set<String> voters = ReplicationCoordinator.resolveRemoteVoters(
				CrossDcMode.SYNC_VOTERS_ACROSS_DC,
				props,
				List.of(
						new ReplicationPeer("voter-1", "127.0.0.1", 1, "dc-b"),
						new ReplicationPeer("learner-1", "127.0.0.1", 2, "dc-b"),
						new ReplicationPeer("local-2", "127.0.0.1", 3, "dc-a")
				),
				"dc-a"
		);
		assertEquals(Set.of("voter-1"), voters);
	}

	@Test
	void asyncShipHasNoRemoteVoters() {
		final CrossDcProps props = new CrossDcProps();
		final Set<String> voters = ReplicationCoordinator.resolveRemoteVoters(
				CrossDcMode.ASYNC_SHIP,
				props,
				List.of(new ReplicationPeer("r1", "127.0.0.1", 1, "dc-b")),
				"dc-a"
		);
		assertTrue(voters.isEmpty());
	}

	@Test
	void syncModeShipsToVotersAndLearners() {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		final CrossDcPublisher publisher = new CrossDcPublisher(
				state,
				CrossDcMode.SYNC_VOTERS_ACROSS_DC,
				1,
				5,
				false,
				1_000L,
				List.of("learner-1"),
				List.of("voter-1")
		);
		publisher.setRemotePeers(List.of(
				new ReplicationPeer("voter-1", "127.0.0.1", 1, "dc-b"),
				new ReplicationPeer("learner-1", "127.0.0.1", 2, "dc-b")
		));
		assertEquals(Set.of("voter-1"), publisher.resolvedVoterIds());

		final AtomicInteger ships = new AtomicInteger();
		publisher.setShipper((peer, segment) -> ships.incrementAndGet());
		publisher.onAppended(upsert("d", 0, 1L, new byte[]{1}, new byte[]{2}));
		assertEquals(2, ships.get(), "voter + learner both receive ship");
		assertFalse(publisher.isRequireRemoteAck());
	}

	@Test
	void asyncShipTargetsConfiguredLearnersOnly() {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		final CrossDcPublisher publisher = new CrossDcPublisher(
				state,
				CrossDcMode.ASYNC_SHIP,
				1,
				5,
				false,
				1_000L,
				List.of("learner-1"),
				List.of()
		);
		publisher.setRemotePeers(List.of(
				new ReplicationPeer("learner-1", "127.0.0.1", 1, "dc-b"),
				new ReplicationPeer("other-remote", "127.0.0.1", 2, "dc-b")
		));
		final AtomicInteger ships = new AtomicInteger();
		final AtomicInteger learnerHits = new AtomicInteger();
		publisher.setShipper((peer, segment) -> {
			ships.incrementAndGet();
			if ("learner-1".equals(peer.id())) {
				learnerHits.incrementAndGet();
			}
		});
		publisher.onAppended(upsert("d", 0, 1L, new byte[]{1}, new byte[]{2}));
		assertEquals(1, ships.get(), "ASYNC ships only configured learners");
		assertEquals(1, learnerHits.get());
	}

	@Test
	void asyncShipAllRemotesWhenLearnersEmpty() {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		final CrossDcPublisher publisher = new CrossDcPublisher(
				state,
				CrossDcMode.ASYNC_SHIP,
				1,
				5,
				false,
				1_000L,
				List.of(),
				List.of()
		);
		publisher.setRemotePeers(List.of(
				new ReplicationPeer("r1", "127.0.0.1", 1, "dc-b"),
				new ReplicationPeer("r2", "127.0.0.1", 2, "dc-b")
		));
		final AtomicInteger ships = new AtomicInteger();
		publisher.setShipper((peer, segment) -> ships.incrementAndGet());
		publisher.onAppended(upsert("d", 0, 1L, new byte[]{1}, new byte[]{2}));
		assertEquals(2, ships.get(), "empty learners -> all remotes (compat)");
	}
}