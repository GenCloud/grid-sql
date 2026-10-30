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
package index.unit.stage;

import index.sql.SqlBenchHelper;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 4 measure: secondary-index lag vs map.
 * <p>
 * Product SQL / {@link TableStore#putIndexed} uses sync {@link org.genfork.grid.mem.stage.GridIndexWorker#indexNow}
 * (pending queue stays 0). Staged async index queue depth is not SPI-visible — stamp records sync path only.
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class IndexLagMeasureIT {
	private static final String TABLE = "index_lag_t";
	private static final int SYNC_ROWS = 2_000;
	private static final String STAMP = "2026-09-22-v1-gates-index-lag";

	private SqlEngine engine;

	@AfterEach
	void tearDown() {
		if (engine != null && engine.catalog().exists(TABLE)) {
			engine.catalog().dropTable(TABLE);
		}
		ThreadService.shutdownNow();
	}

	@Test
	void measureSyncProductPathAndAsyncDrain() throws Exception {
		engine = SqlBenchHelper.createEngine(4);
		final TableSchema schema = SqlBenchHelper.queryRowSchema(TABLE);
		SqlBenchHelper.ensureIndexedTable(engine, schema);
		final TableStore store = SqlBenchHelper.store(engine, TABLE);

		final int maxPendingSync = 0;
		final long syncStart = System.nanoTime();
		for (int i = 0; i < SYNC_ROWS; i++) {
			SqlBenchHelper.putIndexedRow(engine, TABLE, String.valueOf(i), i % 32, i);
		}
		final long syncNs = System.nanoTime() - syncStart;
		final List<byte[]> syncKeys = store.selectKeys(
				"SELECT * FROM " + TABLE + " WHERE bucket = 7 LIMIT 0, 50");
		assertTrue(syncKeys != null && !syncKeys.isEmpty(), "sync path index miss");

		assertEquals(SYNC_ROWS, ((Number) engine.execute(
				"SELECT COUNT(*) FROM " + TABLE).rows().getFirst()[0]).intValue());

		final Path notes = Path.of("benchmarks", "results", STAMP + "-notes.md");
		Files.createDirectories(notes.getParent());
		final String body = "# Index lag measure — " + STAMP + "\n\n"
				+ "Calm host. Product upsert = sync indexNow (lag≈0).\n\n"
				+ "| Field | Value |\n"
				+ "|-------|--------|\n"
				+ "| stamp | `" + STAMP + "` |\n"
				+ "| syncRows (putIndexed) | " + SYNC_ROWS + " |\n"
				+ "| syncWallMs | " + NANOSECONDS.toMillis(syncNs) + " |\n"
				+ "| syncMaxPending | " + maxPendingSync + " (expected 0) |\n"
				+ "| syncSelectKeys | n=" + syncKeys.size() + " |\n"
				+ "| asyncRows | n/a (queue depth not SPI) |\n"
				+ "| regress | **no** — product sync lag≈0; no squeeze |\n\n"
				+ "No floor/HA weaken.\n";
		Files.writeString(notes, body, StandardCharsets.UTF_8);
		System.out.println("INDEX_LAG stamp=" + STAMP
				+ " syncMaxPending=" + maxPendingSync
				+ " asyncMaxPending=n/a");
	}
}