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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * P1: durability on + peer replication off — row data survives process restart via OpLog.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class DurableSoloRestartIT {

	@TempDir
	Path tempDir;

	@Test
	void sqlRowsSurviveRestartWithoutPeerReplication() {
		final Path dataDir = tempDir.resolve("solo");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR)");
			engine.execute("INSERT INTO t (id, v) VALUES (1, 'dur')");
			assertEquals("dur", engine.execute("SELECT v FROM t WHERE id = 1").rows().getFirst()[0]);
			assertNotNull(first.engine().catalog().getStore("t"));
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			final SqlEngine engine = second.engine();
			assertEquals("dur", engine.execute("SELECT v FROM t WHERE id = 1").rows().getFirst()[0]);
		}
	}
}