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

import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.serial.PrimaryKeyCodec;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Map-visible / PK-scan-blind ghost: installCommitted must index sync so SELECT * sees rows.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class MapPkIndexVisibilityIT {

	@TempDir
	Path tempDir;

	@Test
	void installCommittedVisibleToSelectStarImmediately() {
		final SqlEngine engine = SqlBenchHelper.createEngine(4);
		engine.execute("CREATE TABLE ghost_t (id INT PRIMARY KEY, v VARCHAR)");
		final TableStore store = SqlBenchHelper.store(engine, "ghost_t");
		final byte[] key = PrimaryKeyCodec.encodeArgument(store.schema(), 7);
		final byte[] value = RowEncoder.encode(store.schema(), new Object[]{7, "live"});
		final GridEntriesProcessor processor = store.processorForTest(key);
		processor.installCommitted(key, value, false);

		assertNotNull(processor.getCommitted(key));
		final SqlResult all = engine.execute("SELECT id, v FROM ghost_t");
		assertEquals(1, all.rows().size());
		assertEquals(7, ((Number) all.rows().getFirst()[0]).intValue());
		assertEquals("live", all.rows().getFirst()[1]);
	}

	@Test
	void fullHydrateRestartSelectStarSeesSealedRows() {
		final Path dataDir = tempDir.resolve("full-hydrate-ghost");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(4)
				.durability(true)
				.hydrateMode("FULL")
				.build()) {
			first.engine().execute("CREATE TABLE ghost_full (id INT PRIMARY KEY, v VARCHAR)");
			first.engine().execute("INSERT INTO ghost_full (id, v) VALUES (3, 'ITEMS')");
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(4)
				.durability(true)
				.hydrateMode("FULL")
				.build()) {
			final SqlEngine engine = second.engine();
			final SqlResult point = engine.execute(
					"SELECT v FROM ghost_full WHERE id = 3");
			assertEquals(1, point.rows().size());
			assertEquals("ITEMS", point.rows().getFirst()[0]);
			final SqlResult all = engine.execute("SELECT id, v FROM ghost_full");
			assertEquals(1, all.rows().size());
			assertEquals(3, ((Number) all.rows().getFirst()[0]).intValue());
		}
	}
}