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

import org.genfork.grid.catalog.CatalogDdlCompactor;
import org.genfork.grid.catalog.CatalogQualifiedName;
import org.genfork.grid.catalog.FkAction;
import org.genfork.grid.catalog.FkDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.sql.SqlEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compact CREATE TABLE order must emit FK parents before children (recover-safe).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public class CatalogDdlCompactorFkOrderTest {

	@TempDir
	Path tempDir;

	@Test
	void compactEmitsParentBeforeChildDespiteAlphabeticalOrder() {
		final TableCatalog catalog = new TableCatalog();
		catalog.createTable(TableSchema.builder("item_data")
				.primaryKey("id", SqlType.INT)
				.build());
		catalog.createTable(TableSchema.builder("item_commission")
				.primaryKey("id", SqlType.INT)
				.foreignKey(FkDef.of(
						"item_commission_item_data_id_fk",
						"item_commission",
						List.of("id"),
						CatalogQualifiedName.parse("item_data", "public"),
						List.of("id"),
						FkAction.RESTRICT,
						FkAction.RESTRICT))
				.build());
		final List<String> lines = CatalogDdlCompactor.compactLines(catalog);
		int parentAt = -1;
		int childAt = -1;
		for (int i = 0; i < lines.size(); i++) {
			final String lower = lines.get(i).toLowerCase(Locale.ROOT);
			if (lower.startsWith("create table") && lower.contains("item_data")
					&& !lower.contains("item_commission")) {
				parentAt = i;
			}
			if (lower.startsWith("create table") && lower.contains("item_commission")) {
				childAt = i;
			}
		}
		assertTrue(parentAt >= 0, "parent CREATE missing: " + lines);
		assertTrue(childAt >= 0, "child CREATE missing: " + lines);
		assertTrue(parentAt < childAt, "parent must precede child: " + lines);
	}

	@Test
	void recoverSucceedsWhenChildAlphabeticallyBeforeParent() {
		final Path catDir = tempDir.resolve("cat");
		final TableCatalog seed = new TableCatalog(catDir);
		seed.createTable(TableSchema.builder("item_data")
				.primaryKey("id", SqlType.INT)
				.build());
		seed.createTable(TableSchema.builder("item_commission")
				.primaryKey("id", SqlType.INT)
				.foreignKey(FkDef.of(
						"fk_c",
						"item_commission",
						List.of("id"),
						CatalogQualifiedName.parse("item_data", "public"),
						List.of("id"),
						FkAction.RESTRICT,
						FkAction.RESTRICT))
				.build());
		seed.rewriteCompactedDdl();

		final TableCatalog recovered = new TableCatalog(catDir);
		final SqlEngine engine = new SqlEngine(recovered, null, 4);
		engine.recoverPersistedCatalog();
		assertTrue(recovered.exists("item_data"));
		assertTrue(recovered.exists("item_commission"));
	}
}
