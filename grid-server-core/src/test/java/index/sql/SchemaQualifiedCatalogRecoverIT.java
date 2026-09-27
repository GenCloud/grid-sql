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

import org.genfork.grid.catalog.CatalogPersistUtil;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-qualified table recover must not emit dotted CREATE INDEX names.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class SchemaQualifiedCatalogRecoverIT {
	@Test
	void recoverSanitizesDottedPkIndexAndCompacts() throws Exception {
		final Path dir = Files.createTempDirectory("grid-cat-schema-pk");
		final Path catalog = dir.resolve("catalog");
		Files.createDirectories(catalog);
		Files.writeString(catalog.resolve("schemas.list"), "auth_server\n", StandardCharsets.UTF_8);
		Files.writeString(
				catalog.resolve("ddl.sql"),
				"""
						CREATE SCHEMA IF NOT EXISTS auth_server
						CREATE TABLE IF NOT EXISTS auth_server.account_game_server_data (id INT PRIMARY KEY, account_id VARCHAR NOT NULL)
						CREATE INDEX auth_server.account_game_server_data_pk ON auth_server.account_game_server_data (id)
						""",
				StandardCharsets.UTF_8);

		final SqlEngine engine = new SqlEngine(new TableCatalog(dir), null, 4);
		engine.recoverPersistedCatalog();

		assertTrue(engine.catalog().schemaExists("auth_server"));
		assertTrue(engine.catalog().exists("auth_server.account_game_server_data"));
		final IndexDef pk = engine.catalog()
				.requireSchema("auth_server.account_game_server_data")
				.findIndex(CatalogPersistUtil.syntheticPkIndexName("auth_server.account_game_server_data"));
		assertTrue(pk != null);
		assertFalse(pk.name().contains("."));

		final String compacted = Files.readString(catalog.resolve("ddl.sql"), StandardCharsets.UTF_8);
		assertFalse(compacted.contains("CREATE INDEX auth_server."), compacted);
		assertTrue(compacted.contains("CREATE INDEX account_game_server_data_pk ON"));
	}
}