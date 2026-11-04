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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * READ_REPLICA gate fails closed on unhydrated / incomplete tip even when write-path
 * {@code max-stale-lag} would still allow serving-stream lag.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class ReplicaDataCompleteFenceTest {
	private static final String DOMAIN = "replica_complete";
	private static final long HUGE_TIP = 50_000L;

	@TempDir
	Path tmp;

	@Test
	void ensureReplicaReadDeniesUnhydratedLazyTip() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(true);
		props.getReplication().getHa().setMaxStaleLag(100_000L);
		props.getReplication().getHa().setReplicaReadsEnabled(true);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl").toString());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().setClusterId("c-complete");
		props.getReplication().setNodeId("n1");
		props.getReplication().getTransport().setBindPort(0);

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		try {
			final GridEntriesProcessor proc =
					new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN, shard -> shard == 0 ? proc : null, true);
			coord.getOpLog().append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, HUGE_TIP, ReplicationOpType.UPSERT,
					"k".getBytes(StandardCharsets.UTF_8),
					"v".getBytes(StandardCharsets.UTF_8),
					1L, 0L)));

			assertTrue(coord.isReplicaDataIncomplete());
			final OrchidNotSyncedException denied = assertThrows(
					OrchidNotSyncedException.class,
					() -> ReplicaAccessGate.ensureReplicaRead(coord));
			assertTrue(denied.getMessage().contains("stale")
					|| denied.getMessage().contains("apply lag"));
		} finally {
			coord.stop();
		}
	}
}
