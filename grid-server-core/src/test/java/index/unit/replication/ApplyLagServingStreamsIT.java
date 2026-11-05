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
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUTH {@code applyLagStale} must ignore unhydrated LAZY tip debt; serving lag still counts.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class ApplyLagServingStreamsIT {
	private static final String DOMAIN = "lag_serving";
	private static final long HUGE_TIP = 50_000L;

	@TempDir
	Path tmp;

	@Test
	void untouchedLazyTipDoesNotMarkApplyLagStale() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(true);
		props.getReplication().getHa().setMaxStaleLag(100L);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl").toString());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().setClusterId("c-lag");
		props.getReplication().setNodeId("n1");
		props.getReplication().getTransport().setBindPort(0);

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		try {
			final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN, shard -> shard == 0 ? proc : null, true);

			coord.getOpLog().append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, HUGE_TIP, ReplicationOpType.UPSERT,
					"k".getBytes(StandardCharsets.UTF_8),
					"v".getBytes(StandardCharsets.UTF_8),
					1L, 0L)));

			assertTrue(coord.unhydratedTipDebt() > props.getReplication().getHa().getMaxStaleLag(),
					"unhydrated tip debt must be visible for ops/metrics");
			assertFalse(coord.isApplyLagStale(),
					"untouched LAZY tip must not set write-path applyLagStale");
			assertTrue(coord.isReplicaDataIncomplete(),
					"unhydrated tip must mark replica data incomplete for client SELECT");
			assertTrue(coord.isClientApplyLagStale(),
					"READ_REPLICA admission must fence incomplete LAZY tip");
			assertFalse(coord.isAuthApplyLagStale(),
					"PRIMARY AUTH must not fence writer on unhydrated LAZY tip");

			coord.ensureShardHydrated(DOMAIN, 0);
			// After hydrate, applied catches tip (or dump truncates); lag should clear.
			assertFalse(coord.isApplyLagStale());
			assertFalse(coord.isReplicaDataIncomplete());
			assertFalse(coord.isClientApplyLagStale());
			assertFalse(coord.isAuthApplyLagStale());
		} finally {
			coord.stop();
		}
	}
}
