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

import index.sql.udf.UpsertItemUdf;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlRouteClassifier;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-FN: mutating scalar UDF via {@code SELECT fn(...)} in the same session/TX unit.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlMutatingUdfIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void selectMutatingUdfUpsertsRow() {
		engine.execute("CREATE TABLE items (id INT PRIMARY KEY, payload VARCHAR)");
		engine.execute(
				"CREATE FUNCTION item_upsert(id INT, payload VARCHAR) RETURNS INT AS CLASS '"
						+ UpsertItemUdf.class.getName()
						+ "' METHOD 'apply'");

		final SqlResult call = engine.execute("SELECT item_upsert(7, 'hello')");
		assertEquals(1, call.rows().size());
		assertEquals(7, ((Number) call.rows().getFirst()[0]).intValue());

		final SqlResult rows = engine.execute("SELECT id, payload FROM items WHERE id = 7");
		assertEquals(1, rows.rows().size());
		assertEquals(7, ((Number) rows.rows().getFirst()[0]).intValue());
		assertEquals("hello", String.valueOf(rows.rows().getFirst()[1]));
	}

	@Test
	void bareMutatingSelectRoutesWrite() {
		assertEquals(
				SqlRouteClassifier.Route.WRITE,
				SqlRouteClassifier.classify("SELECT item_upsert(1, 'x')"));
	}

	@Test
	void mutatingUdfInsideExplicitTx() {
		engine.execute("CREATE TABLE items (id INT PRIMARY KEY, payload VARCHAR)");
		engine.execute(
				"CREATE FUNCTION item_upsert(id INT, payload VARCHAR) RETURNS INT AS CLASS '"
						+ UpsertItemUdf.class.getName()
						+ "' METHOD 'apply'");

		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "SELECT item_upsert(3, 'tx')");
		engine.execute(session, "COMMIT");

		final SqlResult rows = engine.execute("SELECT payload FROM items WHERE id = 3");
		assertEquals(1, rows.rows().size());
		assertEquals("tx", String.valueOf(rows.rows().getFirst()[0]));
	}

	@Test
	void catalogMarksMutating() {
		engine.execute(
				"CREATE FUNCTION item_upsert(id INT, payload VARCHAR) RETURNS INT AS CLASS '"
						+ UpsertItemUdf.class.getName()
						+ "' METHOD 'apply'");
		assertTrue(engine.catalog().requireFunction("item_upsert").mutating());
	}
}