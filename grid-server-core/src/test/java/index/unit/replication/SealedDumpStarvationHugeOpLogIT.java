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

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Huge OpLog without {@code .sbpt} fail-closes LAZY hydrate; successful sealed dump (gates NONE)
 * clears the fence without raising {@code MAX_OPLOG_DELTA_WITHOUT_SBPT}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SealedDumpStarvationHugeOpLogIT {
	private static final String DOMAIN = "starve_oplog_seal";
	/** Same ceiling as {@code SealedHydrateService.MAX_OPLOG_DELTA_WITHOUT_SBPT}. */
	private static final long MAX_OPLOG_DELTA_WITHOUT_SBPT = 100_000L;
	private static final long PAD_SEQ = MAX_OPLOG_DELTA_WITHOUT_SBPT + 1L;
	private static final byte[] KEY = "starve-k".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL = "starve-v".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tmp;

	@Test
	void dumpThenHydrateClearsFenceWithoutWeakeningLimit() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl").toString());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().setClusterId("c-starve");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		try {
			final GridEntriesProcessor proc = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN, shard -> shard == 0 ? proc : null, true);

			final ReplicationOp pad = OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, 0, PAD_SEQ, ReplicationOpType.UPSERT, KEY, VAL, 1L, 0L));
			coord.getOpLog().append(pad);

			final IllegalStateException gate = assertThrows(IllegalStateException.class,
					() -> coord.ensureShardHydrated(DOMAIN, 0));
			assertTrue(gate.getMessage().contains("without sealed .sbpt"), gate.getMessage());
			assertTrue(gate.getMessage().contains(String.valueOf(PAD_SEQ))
							|| gate.getMessage().contains("exceeds"),
					gate.getMessage());

			final int sealedRows = coord.dumpDomainSnapshot(DOMAIN);
			assertTrue(sealedRows > 0, "dump with gates NONE must produce sealed rows");

			assertDoesNotThrow(() -> coord.ensureShardHydrated(DOMAIN, 0),
					"after sealed dump, LAZY hydrate must pass without raising OpLog ceiling");
		} finally {
			coord.stop();
		}
	}
}
