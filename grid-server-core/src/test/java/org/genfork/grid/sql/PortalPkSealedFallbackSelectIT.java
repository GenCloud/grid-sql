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
package org.genfork.grid.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Package-local: AlwaysTrue SELECT after LAZY seal uses merge path (no false PK missing).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class PortalPkSealedFallbackSelectIT {
	private static final String TABLE = "portal_pk_sealed";
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path temporary;

	@Test
	void alwaysTrueSelectAfterLazySealUsesMergeNotFalseMissing() {
		final Path dataDir = temporary.resolve("portal-exec");
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE
					+ " (id INT PRIMARY KEY, payload INT)");
			engine.execute("INSERT INTO " + TABLE + " (id, payload) VALUES (1, 10)");
			engine.execute("INSERT INTO " + TABLE + " (id, payload) VALUES (2, 20)");
			assertTrue(first.replication().dumpDomainSnapshot(TABLE) >= 2);
		}

		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = second.engine();
			final SqlResult rows = assertDoesNotThrow(
					() -> engine.execute("SELECT id, payload FROM " + TABLE));
			assertEquals(2, rows.rows().size());
		}
	}
}