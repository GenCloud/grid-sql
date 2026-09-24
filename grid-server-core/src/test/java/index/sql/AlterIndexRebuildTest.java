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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan F depth: ALTER ADD/DROP COLUMN with row rewrite + secondary index rebuild;
 * CREATE/DROP INDEX backfill already covered in SqlEngineCatalogDmlTest.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class AlterIndexRebuildTest {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void addColumnThenIndexBackfill() {
		engine.execute("CREATE TABLE alt_add (id INT PRIMARY KEY, bucket INT)");
		engine.execute("INSERT INTO alt_add VALUES (1, 7)");
		engine.execute("INSERT INTO alt_add VALUES (2, 8)");
		engine.execute("ALTER TABLE alt_add ADD COLUMN note VARCHAR");
		assertEquals(3, engine.catalog().requireSchema("alt_add").columnCount());
		engine.execute("CREATE INDEX idx_alt_bucket ON alt_add (bucket)");
		final SqlResult sel = engine.execute("SELECT id FROM alt_add WHERE bucket = 7");
		assertEquals(1, sel.rows().size());
		assertEquals(1, sel.rows().getFirst()[0]);
		final SqlResult note = engine.execute("SELECT note FROM alt_add WHERE id = 1");
		assertEquals(1, note.rows().size());
		assertEquals(null, note.rows().getFirst()[0]);
	}

	@Test
	void dropColumnRewritesRowsAndKeepsIndexQueryable() {
		engine.execute("CREATE TABLE alt_drop (id INT PRIMARY KEY, bucket INT, junk VARCHAR)");
		engine.execute("INSERT INTO alt_drop VALUES (1, 7, 'x')");
		engine.execute("INSERT INTO alt_drop VALUES (2, 7, 'y')");
		engine.execute("INSERT INTO alt_drop VALUES (3, 9, 'z')");
		engine.execute("CREATE INDEX idx_alt_drop_bucket ON alt_drop (bucket)");
		engine.execute("ALTER TABLE alt_drop DROP COLUMN junk");
		assertEquals(2, engine.catalog().requireSchema("alt_drop").columnCount());
		final SqlResult sel = engine.execute("SELECT id FROM alt_drop WHERE bucket = 7");
		assertEquals(2, sel.rows().size());
		final SqlResult star = engine.execute("SELECT * FROM alt_drop WHERE id = 1");
		assertEquals(2, star.rows().getFirst().length);
		assertEquals(1, star.rows().getFirst()[0]);
		assertEquals(7, star.rows().getFirst()[1]);
	}

	@Test
	void dropIndexedColumnRejectedUntilIndexDropped() {
		engine.execute("CREATE TABLE alt_ix (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO alt_ix VALUES (1, 5)");
		engine.execute("CREATE INDEX idx_alt_v ON alt_ix (v)");
		assertThrows(IllegalStateException.class,
				() -> engine.execute("ALTER TABLE alt_ix DROP COLUMN v"));
		engine.execute("DROP INDEX idx_alt_v ON alt_ix");
		engine.execute("ALTER TABLE alt_ix DROP COLUMN v");
		assertEquals(1, engine.catalog().requireSchema("alt_ix").columnCount());
		assertTrue(engine.execute("SELECT id FROM alt_ix WHERE id = 1").rows().size() == 1);
	}
}