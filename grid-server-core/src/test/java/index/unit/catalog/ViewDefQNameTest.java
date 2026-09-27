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

import org.genfork.grid.catalog.CatalogPersistUtil;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableCatalog.ViewDef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ViewDef QName fields + catalogKey map identity (no composite name field).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class ViewDefQNameTest {
	private static final String SELECT_BODY = "SELECT 1";

	@Test
	void publicBareCatalogKey() {
		final TableCatalog catalog = new TableCatalog();
		final ViewDef def = catalog.createView("v", SELECT_BODY, false);
		assertEquals(CatalogPersistUtil.SCHEMA_PUBLIC, def.schemaName());
		assertEquals("v", def.objectName());
		assertEquals("v", def.catalogKey());
		assertEquals(def, catalog.getView("v"));
	}

	@Test
	void qualifiedSchemaObjectFields() {
		final TableCatalog catalog = new TableCatalog();
		catalog.createSchema("analytics", false);
		final ViewDef def = catalog.createView("analytics.v", SELECT_BODY, false);
		assertEquals("analytics", def.schemaName());
		assertEquals("v", def.objectName());
		assertEquals("analytics.v", def.catalogKey());
		assertEquals(def, catalog.getView("analytics.v"));
	}

	@Test
	void rejectsDottedObjectName() {
		assertThrows(IllegalArgumentException.class,
				() -> new ViewDef("public", "a.b", SELECT_BODY, false));
	}
}