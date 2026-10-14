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

import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.orchid.FileDurableOrchidStore;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FULL boot: {@code _catalog} OpLog CREATE TABLE defers nested FULL hydrate until catalog replay ends.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class CatalogOpLogNestedHydrateBootIT {
	private static final String TABLE = "boot_race_t";
	private static final String HYDRATE_FULL = "FULL";
	private static final String CLUSTER_ID = "sql-local";
	private static final String NODE_ID = "sql-solo";
	private static final int SHARDS = 4;
	private static final byte[] DDL_SQL = (
			"CREATE TABLE IF NOT EXISTS " + TABLE
					+ " (id INT NOT NULL PRIMARY KEY, v VARCHAR)"
	).getBytes(StandardCharsets.UTF_8);

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void fullBootWithCatalogDdlAndSealed_doesNotThrow() throws Exception {
		final Path dataDir = tempDir.resolve("boot-race");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_FULL)
				.adaptiveDiskFirst(false)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v VARCHAR)");
			engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (1, 'a')");
			final ReplicationCoordinator coord = first.ownedReplication();
			assertNotNull(coord);
			assertTrue(coord.dumpDomainSnapshot(TABLE) > 0);
		}

		seedCatalogDdlOp(dataDir);

		final SqlServerRuntime second = assertDoesNotThrow(() -> SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_FULL)
				.adaptiveDiskFirst(false)
				.build());
		try (second) {
			final TableStore store = second.engine().catalog().getStore(TABLE);
			assertNotNull(store, "table must exist after catalog OpLog + deferred FULL hydrate");
		}
	}

	private static void seedCatalogDdlOp(Path dataDir) throws Exception {
		final Path nodeDir = dataDir.resolve("replication").resolve(CLUSTER_ID).resolve(NODE_ID);
		try (OpLog opLog = new OpLog(nodeDir, false)) {
			final long tip = Math.max(1L, opLog.lastSeq(ReplicationCoordinator.CATALOG_DOMAIN,
					ReplicationCoordinator.CATALOG_SHARD) + 1L);
			final ReplicationOp ddl = OpLogCodec.withChecksum(new ReplicationOp(
					ReplicationCoordinator.CATALOG_DOMAIN,
					ReplicationCoordinator.CATALOG_SHARD,
					tip,
					ReplicationOpType.DDL,
					new byte[]{0},
					DDL_SQL,
					1L,
					0L));
			opLog.append(ddl);
		}
		try (FileDurableOrchidStore orchid = new FileDurableOrchidStore(nodeDir.resolve("orchid"), false)) {
			final long tip = Math.max(orchid.loadLastCommittedSeq(), 1L);
			orchid.storeLastCommittedSeq(tip);
		}
	}
}
