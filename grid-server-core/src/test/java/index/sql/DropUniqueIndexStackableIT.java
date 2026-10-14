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

import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.replication.snapshot.sealed.SealedCompositeIndexNames;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DROP UNIQUE INDEX must remove RAM STRICT and sealed {@code .sbpt} so stackable rows insert.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class DropUniqueIndexStackableIT {
	private static final String SCHEMA = "shared_game_server";
	private static final String TABLE = SCHEMA + ".item_data";
	private static final String INDEX = "item_data_owner_item_location_uindex";
	private static final List<String> COMPOSITE_COLS = List.of("owner_id", "item_id", "location_data");
	private static final int SHARDS = 4;

	@Test
	void dropUniqueAllowsStackableInventoryRowsAndPurgesSealed() throws Exception {
		final Path dataDir = Files.createTempDirectory("drop-uindex");
		try {
			try (SqlServerRuntime runtime = SqlServerRuntime.builder()
					.dataDir(dataDir)
					.shards(SHARDS)
					.durability(true)
					.hydrateMode("FULL")
					.build()) {
				final SqlEngine engine = runtime.engine();
				engine.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
				engine.execute("CREATE TABLE " + TABLE + " ("
						+ "id BIGINT PRIMARY KEY, "
						+ "owner_id BIGINT, "
						+ "item_id INT, "
						+ "location_data VARCHAR, "
						+ "count INT)");
				engine.execute("CREATE UNIQUE INDEX " + INDEX + " ON " + TABLE
						+ " (owner_id, item_id, location_data)");

				engine.execute("INSERT INTO " + TABLE
						+ " VALUES (1, 100, 57, 'INVENTORY', 1)");
				assertThrows(NonUniqueValueException.class, () -> engine.execute(
						"INSERT INTO " + TABLE + " VALUES (2, 100, 57, 'INVENTORY', 1)"));

				runtime.ownedReplication().dumpDomainSnapshot(TABLE);

				engine.execute("DROP INDEX " + INDEX + " ON " + TABLE);

				final TableStore store = engine.catalog().getStore(TABLE);
				assertNotNull(store);
				assertTrue(store.schema().findIndex(INDEX) == null, "schema index must be gone");
				assertFalse(store.index().hasNamedIndex(INDEX), "RAM named index must be gone");
				assertTrue(store.index().resolveCompositeIndex(COMPOSITE_COLS) == null,
						"composite STRICT tree must be removed");

				assertNoSealedComposite(dataDir, TABLE);

				engine.execute("INSERT INTO " + TABLE
						+ " VALUES (2, 100, 57, 'INVENTORY', 1)");
				engine.execute("INSERT INTO " + TABLE
						+ " VALUES (3, 100, 57, 'INVENTORY', 1)");
			}

			try (SqlServerRuntime runtime = SqlServerRuntime.builder()
					.dataDir(dataDir)
					.shards(SHARDS)
					.durability(true)
					.hydrateMode("FULL")
					.build()) {
				assertNoSealedComposite(dataDir, TABLE);
				runtime.engine().execute("INSERT INTO " + TABLE
						+ " VALUES (4, 100, 57, 'INVENTORY', 1)");
			}
		} finally {
			deleteRecursiveBestEffort(dataDir);
		}
	}

	private static void deleteRecursiveBestEffort(Path root) {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Windows OpLog mmap may linger briefly; not a product assert
				}
			});
		} catch (IOException ignored) {
			// best-effort
		}
	}

	private static void assertNoSealedComposite(Path dataDir, String domain) throws Exception {
		final Path sealedRoot = findSealedRoot(dataDir);
		if (!Files.isDirectory(sealedRoot)) {
			return;
		}
		final String sealedName = SealedCompositeIndexNames.of(COMPOSITE_COLS);
		final String hex = SealedShardPack.domainHex(domain);
		final String needle = "_idx_" + sealedName + ".sbpt";
		try (Stream<Path> walk = Files.list(sealedRoot)) {
			final boolean present = walk
					.map(p -> p.getFileName().toString())
					.anyMatch(name -> name.startsWith(hex + "_") && name.endsWith(needle));
			assertFalse(present, "sealed composite sbpt must be purged after DROP INDEX");
		}
	}

	private static Path findSealedRoot(Path dataDir) throws Exception {
		try (Stream<Path> walk = Files.walk(dataDir)) {
			return walk
					.filter(p -> p.getFileName() != null && "sealed".equals(p.getFileName().toString()))
					.filter(Files::isDirectory)
					.findFirst()
					.orElse(dataDir.resolve("sealed"));
		}
	}
}
