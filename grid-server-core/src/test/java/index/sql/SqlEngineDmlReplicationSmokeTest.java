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
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Single-process smoke: DDL+DML on SqlEngine (replication wiring covered by TableStore registerDomain).
 * Full 2-node Netty ORCHID IT remains in existing replication suites; this gates SQL store path.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SqlEngineDmlReplicationSmokeTest {
	@Test
	void upsertSelectRoundtrip() {
		try {
				final SqlEngine engine = new SqlEngine(new TableCatalog(), null, 4);
			engine.execute("CREATE TABLE peer (id INT PRIMARY KEY, v VARCHAR)");
			engine.execute("INSERT INTO peer (id, v) VALUES (1, 'x')");
			engine.execute("UPDATE peer SET v = v || 'y' WHERE id = 1");
			final SqlResult r = engine.execute("SELECT v FROM peer WHERE id = 1");
			assertEquals("xy", r.rows().getFirst()[0]);
		} finally {
		}
	}
}