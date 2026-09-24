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

import index.sql.udf.DoubleIntUdf;
import index.sql.udf.StaticAddOne;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2.1: {@code CREATE FUNCTION} SPI bind + SELECT/WHERE calls; reject unsafe binds.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlUdfFunctionIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void createFunctionAndCallInSelect() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t VALUES (1, 21)");
		engine.execute("INSERT INTO t VALUES (2, 7)");
		engine.execute(
				"CREATE FUNCTION double_v(x INT) RETURNS INT AS CLASS '"
						+ DoubleIntUdf.class.getName()
						+ "' METHOD 'apply'");

		final SqlResult r = engine.execute("SELECT id, double_v(v) AS d FROM t WHERE id = 1");
		assertEquals(1, r.rows().size());
		assertEquals(1, ((Number) r.rows().getFirst()[0]).intValue());
		assertEquals(42, ((Number) r.rows().getFirst()[1]).intValue());
	}

	@Test
	void udfInWhereFiltersRows() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t VALUES (1, 3)");
		engine.execute("INSERT INTO t VALUES (2, 10)");
		engine.execute(
				"CREATE FUNCTION double_v(x INT) RETURNS INT AS CLASS '"
						+ DoubleIntUdf.class.getName()
						+ "' METHOD 'apply'");

		final SqlResult r = engine.execute("SELECT id FROM t WHERE double_v(v) = 6");
		assertEquals(1, r.rows().size());
		assertEquals(1, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void staticMethodBindWorks() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t VALUES (1, 40)");
		engine.execute(
				"CREATE FUNCTION add1(x INT) RETURNS INT AS CLASS '"
						+ StaticAddOne.class.getName()
						+ "' METHOD 'apply'");

		final SqlResult r = engine.execute("SELECT add1(v) FROM t");
		assertEquals(1, r.rows().size());
		assertEquals(41, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void rejectUnsafeScriptEngineBind() {
		final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> engine.execute(
						"CREATE FUNCTION bad(x INT) RETURNS INT AS CLASS 'javax.script.ScriptEngineManager' METHOD 'apply'")
		);
		assertTrue(ex.getMessage().toLowerCase().contains("unsafe"));
	}

	@Test
	void rejectRuntimeBind() {
		assertThrows(
				IllegalArgumentException.class,
				() -> engine.execute(
						"CREATE FUNCTION bad(x INT) RETURNS INT AS CLASS 'java.lang.Runtime' METHOD 'apply'")
		);
	}

	@Test
	void rejectMissingMethodOnNonUdfClass() {
		assertThrows(
				IllegalArgumentException.class,
				() -> engine.execute(
						"CREATE FUNCTION bad(x INT) RETURNS INT AS CLASS '"
								+ StaticAddOne.class.getName()
								+ "' METHOD 'missing'")
		);
	}

	@Test
	void dropFunctionRemovesBinding() {
		engine.execute(
				"CREATE FUNCTION double_v(x INT) RETURNS INT AS CLASS '"
						+ DoubleIntUdf.class.getName()
						+ "' METHOD 'apply'");
		engine.execute("DROP FUNCTION double_v");
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t VALUES (1, 1)");
		assertThrows(IllegalStateException.class, () -> engine.execute("SELECT double_v(v) FROM t"));
	}

	@Test
	void selectAndWhereTogether() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t VALUES (1, 2)");
		engine.execute("INSERT INTO t VALUES (2, 5)");
		engine.execute(
				"CREATE FUNCTION double_v(x INT) RETURNS INT AS CLASS '"
						+ DoubleIntUdf.class.getName()
						+ "' METHOD 'apply'");

		final SqlResult r = engine.execute(
				"SELECT id, double_v(v) AS d FROM t WHERE double_v(v) > 5");
		assertEquals(1, r.rows().size());
		assertEquals(2, ((Number) r.rows().getFirst()[0]).intValue());
		assertEquals(10, ((Number) r.rows().getFirst()[1]).intValue());
		final Set<Integer> ids = new HashSet<>();
		ids.add(((Number) r.rows().getFirst()[0]).intValue());
		assertTrue(ids.contains(2));
	}
}
