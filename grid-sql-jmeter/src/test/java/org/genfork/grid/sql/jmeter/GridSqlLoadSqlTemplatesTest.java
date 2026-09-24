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
package org.genfork.grid.sql.jmeter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Defaults stay byte-identical to historical load-SLO SQL; props override templates.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class GridSqlLoadSqlTemplatesTest {

	@Test
	void defaultsMatchHistoricalUpsertAndEq() {
		assertTrue(GridSqlLoadSqlTemplates.defaultsMatchHistoricalShape());
		final GridSqlLoadSqlTemplates t = GridSqlLoadSqlTemplates.defaults();
		assertEquals(
				"SELECT id, val, n FROM load_slo_a WHERE id = 42 LIMIT 1",
				t.renderEqLimit(42));
		assertEquals(
				"SELECT id, val FROM load_slo_a WHERE id = 9 LIMIT 1",
				t.renderShortTxSelect(9));
		assertEquals(
				"UPDATE load_slo_a SET n = n + 1 WHERE id = 9",
				t.renderShortTxUpdate(9));
		assertEquals(
				"SELECT COUNT(*) FROM load_slo_a JOIN load_slo_b ON id = a_id WHERE a_id = 5",
				t.renderCountJoin(5));
	}

	@Test
	void propOverrideChangesTableAndSql() {
		final GridSqlLoadSqlTemplates t = GridSqlLoadSqlTemplates.resolve(key -> {
			if (GridSqlLoadSqlTemplates.PROP_TABLE_A.equals(key)) {
				return "custom_a";
			}
			if (GridSqlLoadSqlTemplates.PROP_SQL_EQ_LIMIT.equals(key)) {
				return "SELECT id FROM ${tableA} WHERE id = ${id}";
			}
			return null;
		});
		assertEquals("custom_a", t.tableA());
		assertEquals("SELECT id FROM custom_a WHERE id = 3", t.renderEqLimit(3));
	}
}
