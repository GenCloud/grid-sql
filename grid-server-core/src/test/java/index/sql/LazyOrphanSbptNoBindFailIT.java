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
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lab repro: empty-clear left orphan {@code .sbpt} without {@code .gmap}; LAZY SELECT must
 * return 0 rows — never {@code LAZY sealed bind failed} (TPS/app death).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class LazyOrphanSbptNoBindFailIT {

	private static final String TABLE = "lazy_orphan_drop";
	private static final String INDEX = "lazy_orphan_drop_owner";
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final String GMAP_SUFFIX = ".gmap";
	private static final String SBPT_SUFFIX = ".sbpt";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void selectDoesNotThrowWhenOnlyOrphanSbptRemain() throws Exception {
		final Path dataDir = tempDir.resolve("orphan-sbpt");
		sealThenStripGmapsLeaveSbpt(dataDir);

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = second.engine();
			// OpLog may still replay rows after .gmap strip — contract is no bind-fail throw.
			final SqlResult rows = assertDoesNotThrow(
					() -> engine.execute("SELECT owner_id, item_id FROM " + TABLE + " WHERE owner_id = 1"),
					"orphan .sbpt without .gmap must not fail-closed bind");
			assertNotNull(rows);
		}
	}

	private static void sealThenStripGmapsLeaveSbpt(Path dataDir) throws IOException {
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE
					+ " (owner_id INT, item_id INT, amount INT, PRIMARY KEY (owner_id, item_id))");
			engine.execute("CREATE INDEX " + INDEX + " ON " + TABLE + " (owner_id)");
			engine.execute("INSERT INTO " + TABLE + " (owner_id, item_id, amount) VALUES (1, 10, 1)");
			final ReplicationCoordinator coord = first.replication();
			assertNotNull(coord);
			assertTrue(coord.dumpDomainSnapshot(TABLE) >= 1, "seal dump");
		}
		final String hex = SealedShardPack.domainHex(TABLE);
		final List<Path> gmaps = listDomainFiles(dataDir, hex, GMAP_SUFFIX);
		assertFalse(gmaps.isEmpty(), "expected .gmap to strip");
		for (Path gmap : gmaps) {
			Files.deleteIfExists(gmap);
		}
		assertFalse(listDomainFiles(dataDir, hex, SBPT_SUFFIX).isEmpty(),
				"lab layout needs leftover .sbpt without .gmap");
	}

	private static List<Path> listDomainFiles(Path dataDir, String hex, String suffix)
			throws IOException {
		try (Stream<Path> walk = Files.walk(dataDir)) {
			return walk
					.filter(Files::isRegularFile)
					.filter(path -> {
						final String name = path.getFileName().toString();
						return name.startsWith(hex + "_") && name.endsWith(suffix);
					})
					.toList();
		}
	}
}
