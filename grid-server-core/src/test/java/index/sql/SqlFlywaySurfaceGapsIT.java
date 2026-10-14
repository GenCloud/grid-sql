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

import java.time.Instant;
import java.time.LocalDate;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway / JDBC tooling SQL surface: boolean WHERE, COUNT aggregates, JDBC escapes, clocks.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlFlywaySurfaceGapsIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void whereFalseSelectReturnsNoRows() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t VALUES (1, 10)");
		final SqlResult r = engine.execute("SELECT id FROM t WHERE FALSE");
		assertTrue(r.rows().isEmpty());
	}

	@Test
	void whereTrueSelectReturnsAllRows() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO t VALUES (1, 10)");
		engine.execute("INSERT INTO t VALUES (2, 20)");
		final SqlResult r = engine.execute("SELECT id FROM t WHERE TRUE ORDER BY id");
		assertEquals(2, r.rows().size());
	}

	@Test
	void countStarWithoutGroupByOnEmptyTable() {
		engine.execute("CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY)");
		final SqlResult r = engine.execute("SELECT COUNT(*) FROM flyway_schema_history");
		assertEquals(1, r.rows().size());
		assertEquals(0, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void countOneWithoutGroupByMatchesCountStar() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY)");
		engine.execute("INSERT INTO t VALUES (1)");
		engine.execute("INSERT INTO t VALUES (2)");
		final SqlResult star = engine.execute("SELECT COUNT(*) FROM t");
		final SqlResult one = engine.execute("SELECT COUNT(1) FROM t");
		assertEquals(((Number) star.rows().getFirst()[0]).intValue(),
				((Number) one.rows().getFirst()[0]).intValue());
	}

	@Test
	void countStarWhereFalseIsZero() {
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY)");
		engine.execute("INSERT INTO t VALUES (1)");
		final SqlResult r = engine.execute("SELECT COUNT(*) FROM t WHERE FALSE");
		assertEquals(0, ((Number) r.rows().getFirst()[0]).intValue());
	}

	@Test
	void jdbcEscapeTimestampInInsertAndSelect() {
		engine.execute("CREATE TABLE ev (id INT PRIMARY KEY, at TIMESTAMPTZ)");
		engine.execute("INSERT INTO ev (id, at) VALUES (1, {ts '2020-06-15 12:30:00'})");
		final SqlResult r = engine.execute("SELECT at FROM ev WHERE id = 1");
		assertEquals(1, r.rows().size());
		assertInstanceOf(Instant.class, r.rows().getFirst()[0]);
	}

	@Test
	void jdbcEscapeDateInInsert() {
		engine.execute("CREATE TABLE ev (id INT PRIMARY KEY, d DATE)");
		engine.execute("INSERT INTO ev (id, d) VALUES (1, {d '2020-06-15'})");
		final SqlResult r = engine.execute("SELECT d FROM ev WHERE id = 1");
		assertEquals(LocalDate.of(2020, 6, 15), r.rows().getFirst()[0]);
	}

	@Test
	void createTableDefaultCurrentTimestampInsertOmitsColumn() {
		engine.execute("CREATE TABLE hist ("
				+ "installed_rank INT PRIMARY KEY, "
				+ "installed_on TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP)");
		engine.execute("INSERT INTO hist (installed_rank) VALUES (1)");
		final SqlResult r = engine.execute("SELECT installed_on FROM hist WHERE installed_rank = 1");
		assertInstanceOf(Instant.class, r.rows().getFirst()[0]);
	}
}
