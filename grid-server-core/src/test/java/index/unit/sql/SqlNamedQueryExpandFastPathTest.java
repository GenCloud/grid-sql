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
package index.unit.sql;

import org.genfork.grid.sql.SqlNamedQueryExpand;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * No-view / no-WITH expand must be identity (skip ANTLR) for Capacity EQ hot path.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlNamedQueryExpandFastPathTest {
	@Test
	void plainSelectWithoutViewsIsIdentity() {
		final String sql = "SELECT id FROM load_slo_a WHERE id = 42 LIMIT 1";
		final String out = SqlNamedQueryExpand.expand(sql, Map.of());
		assertSame(sql, out);
	}

	@Test
	void withClauseDoesNotUseIdentityReference() {
		// Valid CTE body (FROM required) — expand must leave identity fast-path and inline.
		final String sql = "WITH c AS (SELECT id FROM t) SELECT * FROM c";
		final String out = SqlNamedQueryExpand.expand(sql, Map.of());
		assertNotSame(sql, out, "WITH must enter expandUncached (not identity fast-path)");
		assertEquals("SELECT id FROM t", out);
	}
}