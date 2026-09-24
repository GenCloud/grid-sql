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
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2: LAZY hydrate — cold start does not preload; PK get triggers shard-touch.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class LazyHydrateRestartIT {

	@TempDir
	Path tempDir;

	@Test
	void lazyHydrateLoadsOnPkRead() {
		final Path dataDir = tempDir.resolve("lazy");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(4)
				.durability(true)
				.hydrateMode("FULL")
				.build()) {
			first.engine().execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
			first.engine().execute("INSERT INTO t (id, v) VALUES (1, 'lazy')");
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(4)
				.durability(true)
				.hydrateMode("LAZY")
				.build()) {
			final SqlEngine engine = second.engine();
			assertEquals("lazy", engine.execute("SELECT v FROM t WHERE id = 1").rows().getFirst()[0]);
		}
	}
}