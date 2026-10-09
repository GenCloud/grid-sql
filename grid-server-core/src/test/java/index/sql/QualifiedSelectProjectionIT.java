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

import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JOOQ-style {@code table.col} projection must resolve on single-table SELECT.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class QualifiedSelectProjectionIT {
	@TempDir
	Path tempDir;

	@Test
	void tableQualifiedProjectionResolves() {
		final Path dataDir = tempDir.resolve("qual-proj");
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(false)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute(
					"CREATE TABLE bitset_range (server_id INT NOT NULL, biset_type VARCHAR, "
							+ "min_value INT NOT NULL, max_value INT NOT NULL, "
							+ "PRIMARY KEY (server_id, biset_type))");
			engine.execute("INSERT INTO bitset_range VALUES (1, 'x', 10, 20)");
			final SqlResult rows = engine.execute(
					"SELECT bitset_range.min_value, bitset_range.max_value FROM bitset_range "
							+ "WHERE bitset_range.server_id = 1 AND bitset_range.biset_type = 'x'");
			assertEquals(1, rows.rows().size());
			assertEquals(10, ((Number) rows.rows().getFirst()[0]).intValue());
			assertEquals(20, ((Number) rows.rows().getFirst()[1]).intValue());
			final SqlResult bare = engine.execute("SELECT min_value FROM bitset_range WHERE server_id = 1");
			assertEquals(10, ((Number) bare.rows().getFirst()[0]).intValue());
		}
	}
}
