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

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.orchid.FileDurableOrchidStore;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog recover must not force LAZY full-domain hydrate (huge OpLog fail-closed gate).
 * <p>
 * Lab shape: OpLog tip after sealed wm exceeds 100000 without {@code .sbpt}. Post-recover
 * secondary rebuild must stay FULL-only — on LAZY it would call {@code ensureAllShardsHydrated}
 * and fail boot. DDL replay still skips per-CREATE backfill; LAZY postings fill on demand.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class CatalogRecoverLazyNoForceHydrateIT {
	private static final String TABLE = "recover_lazy_idx";
	private static final String INDEX = "recover_lazy_idx_v";
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final String CLUSTER_ID = "sql-local";
	private static final String NODE_ID = "sql-solo";
	/** Same ceiling as {@code SealedHydrateService.MAX_OPLOG_DELTA_WITHOUT_SBPT}. */
	private static final long MAX_OPLOG_DELTA_WITHOUT_SBPT = 100_000L;
	private static final long PAD_SEQ = MAX_OPLOG_DELTA_WITHOUT_SBPT + 1L;
	private static final byte[] PAD_KEY = "pad-key".getBytes(StandardCharsets.UTF_8);
	private static final byte[] PAD_VAL = "pad-val".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tempDir;

	@Test
	void recoverPersistedCatalogDoesNotForceLazyHydrateOrHitHugeOpLogGate() throws Exception {
		final Path dataDir = tempDir.resolve("recover-lazy");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.adaptiveDiskFirst(false)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
			engine.execute("CREATE INDEX " + INDEX + " ON " + TABLE + " (v)");
			engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (1, 'a')");
			engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (2, 'b')");
		}

		padHugeOpLogTipAndOrchidTip(dataDir);

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.adaptiveDiskFirst(false)
				.build()) {
			final SqlEngine engine = second.engine();
			final TableStore store = engine.catalog().getStore(TABLE);
			assertNotNull(store, "table must exist after catalog recover");
			assertNotNull(store.schema().findIndex(INDEX), "secondary index must remain in schema");

			final IllegalStateException gate = assertThrows(IllegalStateException.class,
					store::rebuildAllSecondaryIndexes);
			assertTrue(gate.getMessage().contains("without sealed .sbpt"),
					"huge OpLog gate must stay fail-closed when hydrate is forced: " + gate.getMessage());
		}
	}

	/**
	 * Raise OpLog tip past the fail-closed delta and advance ORCHID tip so reopen BARRIERs
	 * append after the pad (lab-shaped: large unsealed tail, wm=0, no {@code .sbpt}).
	 */
	private static void padHugeOpLogTipAndOrchidTip(Path dataDir) throws Exception {
		final Path nodeDir = dataDir.resolve("replication").resolve(CLUSTER_ID).resolve(NODE_ID);
		try (OpLog opLog = new OpLog(nodeDir, false)) {
			for (int shard = 0; shard < SHARDS; shard++) {
				final long tip = opLog.lastSeq(TABLE, shard);
				final long padSeq = Math.max(PAD_SEQ, tip + 1L);
				final ReplicationOp pad = OpLogCodec.withChecksum(new ReplicationOp(
						TABLE, shard, padSeq, ReplicationOpType.UPSERT, PAD_KEY, PAD_VAL, 1L, 0L));
				opLog.append(pad);
			}
		}
		try (FileDurableOrchidStore orchid = new FileDurableOrchidStore(nodeDir.resolve("orchid"), false)) {
			orchid.storeLastCommittedSeq(PAD_SEQ);
		}
	}
}
