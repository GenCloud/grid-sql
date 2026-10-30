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
import org.genfork.grid.catalog.SyntheticPkIndexUtil;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DROP INDEX on the synthetic {@code table_pk} must fail closed without stripping the RAM PK tree.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class DropSyntheticPkIndexRejectedTest {
	private static final String TABLE = "pk_drop_guard";
	private static final int SHARD_COUNT = 4;

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, SHARD_COUNT);
	}

	@Test
	void dropSyntheticPkIndexRejectedAndSelectStillWorks() {
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (1, 10)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (2, 20)");

		final String pkName = CatalogPersistUtil.syntheticPkIndexName(TABLE);
		final IllegalStateException dropped = assertThrows(
				IllegalStateException.class,
				() -> engine.execute("DROP INDEX " + pkName + " ON " + TABLE));
		assertTrue(
				dropped.getMessage().contains(SyntheticPkIndexUtil.MSG_CANNOT_DROP_PRIMARY_KEY_INDEX),
				() -> "expected PRIMARY KEY drop rejection, got: " + dropped.getMessage());

		final SqlResult rows = engine.execute("SELECT id FROM " + TABLE);
		assertEquals(2, rows.rows().size());
	}
}
