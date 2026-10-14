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
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compacted ddl must keep {@code CREATE UNIQUE INDEX} for {@link IndexType#STRICT}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public class CatalogDdlCompactorUniqueIndexTest {
	@Test
	void compactEmitsUniqueKeywordForStrictSecondary() {
		final TableCatalog catalog = new TableCatalog();
		catalog.createTable(TableSchema.builder("event_schedulers")
				.primaryKey("id", SqlType.INT)
				.column("event_name", SqlType.VARCHAR)
				.column("scheduler_name", SqlType.VARCHAR)
				.index(IndexDef.of(
						"event_schedulers_event_scheduler_uindex",
						IndexType.STRICT,
						"event_name",
						"scheduler_name"))
				.build());
		final List<String> lines = CatalogDdlCompactor.compactLines(catalog);
		final boolean found = lines.stream()
				.map(l -> l.toLowerCase(Locale.ROOT))
				.anyMatch(l -> l.contains("create unique index")
						&& l.contains("event_schedulers_event_scheduler_uindex"));
		assertTrue(found, String.join("\n", lines));
	}
}
