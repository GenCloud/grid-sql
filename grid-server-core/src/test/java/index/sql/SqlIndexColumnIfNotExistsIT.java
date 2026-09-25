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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CREATE INDEX / ADD COLUMN IF NOT EXISTS and DROP INDEX IF EXISTS idempotency.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlIndexColumnIfNotExistsIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void indexAndColumnIfNotExistsAreIdempotent() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, val VARCHAR)");
		engine.execute("CREATE INDEX IF NOT EXISTS t_val_idx ON t (val)");
		engine.execute("CREATE INDEX IF NOT EXISTS t_val_idx ON t (val)");
		engine.execute("ALTER TABLE t ADD COLUMN IF NOT EXISTS extra INT");
		engine.execute("ALTER TABLE t ADD COLUMN IF NOT EXISTS extra INT");
		engine.execute("DROP INDEX IF EXISTS missing_idx ON t");
		engine.execute("DROP INDEX IF EXISTS t_val_idx ON t");
		engine.execute("DROP INDEX IF EXISTS t_val_idx ON t");
	}
}