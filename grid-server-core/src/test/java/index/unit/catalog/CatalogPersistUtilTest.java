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

import org.genfork.grid.catalog.CatalogDdlJournalUtil;
import org.genfork.grid.catalog.CatalogPersistUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Object-local index / name helpers and ddl.sql migrate-on-read.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class CatalogPersistUtilTest {
	@Test
	void objectPartOfStripsSchema() {
		assertEquals("account_game_server_data", CatalogPersistUtil.objectPartOf("auth_server.account_game_server_data"));
		assertEquals("t", CatalogPersistUtil.objectPartOf("t"));
		assertEquals("public", CatalogPersistUtil.schemaPartOf("t"));
		assertEquals("auth_server", CatalogPersistUtil.schemaPartOf("auth_server.account_game_server_data"));
	}

	@Test
	void syntheticPkIsObjectLocal() {
		assertEquals(
				"account_game_server_data_pk",
				CatalogPersistUtil.syntheticPkIndexName("auth_server.account_game_server_data"));
		assertEquals("t_pk", CatalogPersistUtil.syntheticPkIndexName("t"));
	}

	@Test
	void journalSanitizeCreateIndexName() {
		final String bad = "CREATE INDEX auth_server.account_game_server_data_pk ON auth_server.account_game_server_data (id)";
		final String ok = CatalogDdlJournalUtil.sanitizeLine(bad);
		assertEquals(
				"CREATE INDEX account_game_server_data_pk ON auth_server.account_game_server_data (id)",
				ok);
		final List<String> lines = CatalogDdlJournalUtil.sanitizeLines(List.of(bad, "CREATE SCHEMA IF NOT EXISTS auth_server"));
		assertEquals("CREATE SCHEMA IF NOT EXISTS auth_server", lines.get(1));
	}
}