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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan F: peers reject stale DDL schema epoch (strictly behind max applied).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SchemaEpochPeerRejectTest {
	@Test
	void rejectStaleDdlEpoch() {
		try {
			final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 4);
			engine.execute("CREATE TABLE e (id INT PRIMARY KEY)");
			engine.execute("ALTER TABLE e ADD COLUMN note VARCHAR");
			final long applied = engine.catalog().maxAppliedDdlEpoch();
			assertTrue(applied >= 2L);
			assertThrows(IllegalStateException.class,
					() -> engine.applyReplicatedDdl("CREATE TABLE e2 (id INT PRIMARY KEY)", applied - 1L));
		} finally {
		}
	}
}