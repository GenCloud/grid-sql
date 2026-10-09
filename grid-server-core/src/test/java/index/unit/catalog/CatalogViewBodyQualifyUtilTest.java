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
package index.unit.catalog;

import org.genfork.grid.catalog.CatalogViewBodyQualifyUtil;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableCatalog.ViewDef;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VIEW body qualify is a DDL/register edge; expand snapshot must not re-parse.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public class CatalogViewBodyQualifyUtilTest {
	private static final String SCHEMA = "analytics";
	private static final String BARE_JOIN =
			"SELECT b.id, o.v FROM base b JOIN other o ON o.base_id = b.id";

	@Test
	void qualifiesBareFromAndJoinUnderViewSchema() {
		final String out = CatalogViewBodyQualifyUtil.qualifyUnqualifiedTables(BARE_JOIN, SCHEMA);
		final String lower = out.toLowerCase(Locale.ROOT);
		assertTrue(lower.contains("analytics.base"));
		assertTrue(lower.contains("analytics.other"));
	}

	@Test
	void leavesAlreadyQualifiedRefsUnchanged() {
		final String sql = "SELECT id FROM analytics.base";
		assertEquals(sql, CatalogViewBodyQualifyUtil.qualifyUnqualifiedTables(sql, SCHEMA));
	}

	@Test
	void publicSchemaKeepsBareTableNames() {
		final String sql = "SELECT id FROM base";
		assertEquals(sql, CatalogViewBodyQualifyUtil.qualifyUnqualifiedTables(sql, "public"));
	}

	@Test
	void createViewStoresQualifiedBodyAndSnapshotIsStable() {
		final TableCatalog catalog = new TableCatalog();
		catalog.createSchema(SCHEMA, false);
		final ViewDef def = catalog.createView(SCHEMA + ".v", BARE_JOIN, false);
		assertTrue(def.selectSql().toLowerCase(Locale.ROOT).contains("analytics.base"));
		final Map<String, String> first = catalog.viewSelectBodies();
		final Map<String, String> second = catalog.viewSelectBodies();
		assertSame(first, second);
		assertEquals(def.selectSql(), first.get(SCHEMA + ".v"));
	}
}
