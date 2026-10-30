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

import java.util.List;
import java.util.Locale;

import org.genfork.grid.catalog.CatalogDdlCompactor;
import org.genfork.grid.catalog.CatalogPersistUtil;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compacted ddl must not emit CREATE UNIQUE for synthetic {@code table_pk}; secondary UNIQUE stays.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class CatalogDdlCompactorSkipSyntheticPkTest {
	private static final String TABLE = "compact_skip_pk";
	private static final String SECONDARY = "compact_skip_pk_v_uindex";

	@Test
	void compactSkipsSyntheticPkEmitsSecondaryUnique() {
		final TableCatalog catalog = new TableCatalog();
		catalog.createTable(TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.INT)
				.column("v", SqlType.INT)
				.index(IndexDef.of(SECONDARY, IndexType.STRICT, "v"))
				.build());
		final List<String> lines = CatalogDdlCompactor.compactLines(catalog);
		final String joined = String.join("\n", lines).toLowerCase(Locale.ROOT);
		final String pkName = CatalogPersistUtil.syntheticPkIndexName(TABLE).toLowerCase(Locale.ROOT);
		assertFalse(
				joined.contains("create unique index " + pkName + " "),
				() -> "synthetic PK must not be emitted:\n" + String.join("\n", lines));
		assertTrue(
				joined.contains("create unique index " + SECONDARY.toLowerCase(Locale.ROOT)),
				() -> "secondary UNIQUE must remain:\n" + String.join("\n", lines));
		assertTrue(
				joined.contains("primary key (id)"),
				() -> "CREATE TABLE must keep PRIMARY KEY:\n" + String.join("\n", lines));
	}
}
